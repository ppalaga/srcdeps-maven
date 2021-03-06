/**
 * Copyright 2015-2018 Maven Source Dependencies
 * Plugin contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.srcdeps.mvn.localrepo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.LocalArtifactRegistration;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.LocalMetadataRegistration;
import org.eclipse.aether.repository.LocalMetadataRequest;
import org.eclipse.aether.repository.LocalMetadataResult;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.srcdeps.core.BuildException;
import org.srcdeps.core.BuildMetadataStore;
import org.srcdeps.core.BuildRequest;
import org.srcdeps.core.BuildService;
import org.srcdeps.core.ConfigurationQueryService;
import org.srcdeps.core.ConfigurationQueryService.ScmRepositoryResult;
import org.srcdeps.core.FetchId;
import org.srcdeps.core.FetchLog;
import org.srcdeps.core.Gav;
import org.srcdeps.core.GavSet;
import org.srcdeps.core.GavSetWalker;
import org.srcdeps.core.ScmService;
import org.srcdeps.core.SrcVersion;
import org.srcdeps.core.config.BuilderIo;
import org.srcdeps.core.config.Configuration;
import org.srcdeps.core.config.ScmRepository;
import org.srcdeps.core.fs.BuildDirectoriesManager;
import org.srcdeps.core.fs.PathLock;
import org.srcdeps.core.fs.PathLocker;
import org.srcdeps.core.fs.PersistentBuildMetadataStore;
import org.srcdeps.core.shell.IoRedirects;
import org.srcdeps.core.util.SrcdepsCoreUtils;
import org.srcdeps.mvn.config.ConfigurationProducer;

/**
 * A {@link LocalRepositoryManager} able to build the requested artifacts from their sources.
 *
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 */
public class SrcdepsLocalRepositoryManager implements LocalRepositoryManager {
    private static final Logger log = LoggerFactory.getLogger(SrcdepsLocalRepositoryManager.class);

    private static List<String> enhanceBuildArguments(List<String> buildArguments, Path configurationLocation,
            String localRepo) {
        List<String> result = new ArrayList<>();
        for (String arg : buildArguments) {
            if (arg.startsWith("-Dmaven.repo.local=")) {
                /* We won't touch maven.repo.local set in the user's config */
                log.debug("srcdeps: Forwarding [{}] to the nested build as set in [{}]", arg, configurationLocation);
                return buildArguments;
            }
            result.add(arg);
        }

        String arg = "-Dmaven.repo.local=" + localRepo;
        log.debug("srcdeps: Forwarding [{}] from the outer Maven build to the nested build", arg);
        result.add(arg);

        return Collections.unmodifiableList(result);
    }

    void uninstallGavSet(ScmRepository currentRepo, GavSetWalker gavSetWalker) throws IOException {
        final GavSetWalker.GavPathCollector paths = new GavSetWalker.GavPathCollector();
        gavSetWalker.walk(paths);
        final Map<Path, Gav> gavPaths = paths.getGavPaths();
        log.debug("srcdeps: Uninstalling [{}] GAVs before rebuilding them", gavPaths.size());
        final List<ScmRepository> repos = configuration.getRepositories();
        for (Entry<Path, Gav> en : gavPaths.entrySet()) {
            final Path gavDir = en.getKey();
            final Gav gav = en.getValue();
            for (ScmRepository repo : repos) {
                if (currentRepo != repo) {
                    if (repo.getGavSet().contains(gav.getGroupId(), gav.getArtifactId(), gav.getVersion())) {
                        log.error(
                                "srcdeps: Cannot rebuild SCM repository [{}] because it includes artifact [{}] that is included by another SCM repository [{}]. Adjust includes/excludes of those repositories in srcdeps.yaml and retry",
                                currentRepo.getId(), gav.toString(), repo.getId());
                    }
                }
            }
            log.debug("srcdeps: Uninstalling [{}]", gavDir);
            SrcdepsCoreUtils.deleteDirectory(gavDir);
        }
    }

    private final BuildDirectoriesManager buildDirectoriesManager;

    private final BuildMetadataStore buildMetadataStore;
    private final BuildService buildService;
    private final Configuration configuration;
    private final ConfigurationProducer configurationProducer;
    private final ConfigurationQueryService configurationQueryService;
    private final LocalRepositoryManager delegate;
    private final FetchLog fetchLog;
    private final ScmService scmService;
    private final Path scrdepsDir;

    public SrcdepsLocalRepositoryManager(LocalRepositoryManager delegate, BuildService buildService,
            ScmService scmService, PathLocker<SrcVersion> pathLocker, ConfigurationProducer configurationProducer) {
        super();
        this.delegate = delegate;
        this.buildService = buildService;
        this.scmService = scmService;
        this.scrdepsDir = delegate.getRepository().getBasedir().toPath().getParent().resolve("srcdeps");
        this.buildMetadataStore = new PersistentBuildMetadataStore(scrdepsDir.resolve("build-metadata"));
        this.buildDirectoriesManager = new BuildDirectoriesManager(scrdepsDir, pathLocker);
        this.configurationProducer = configurationProducer;
        this.fetchLog = new FetchLog();
        this.configuration = configurationProducer.getConfiguration();
        this.configurationQueryService = new ConfigurationQueryService(this.configuration);
    }

    /**
     * Delegated to {@link #delegate}
     *
     * @see org.eclipse.aether.repository.LocalRepositoryManager#add(org.eclipse.aether.RepositorySystemSession,
     *      org.eclipse.aether.repository.LocalArtifactRegistration)
     */
    @Override
    public void add(RepositorySystemSession session, LocalArtifactRegistration request) {
        delegate.add(session, request);
    }

    /**
     * Delegated to {@link #delegate}
     *
     * @see org.eclipse.aether.repository.LocalRepositoryManager#add(org.eclipse.aether.RepositorySystemSession,
     *      org.eclipse.aether.repository.LocalMetadataRegistration)
     */
    @Override
    public void add(RepositorySystemSession session, LocalMetadataRegistration request) {
        delegate.add(session, request);
    }

    private LocalArtifactResult buildDependency(Artifact artifact, ScmRepository scmRepo, LocalArtifactResult result,
            SrcVersion srcVersion, RepositorySystemSession session, LocalArtifactRequest request) {
        final FetchId fetchId = new FetchId(scmRepo.getId(), scmRepo.getUrls());
        if (fetchLog.contains(fetchId)) {
            log.debug(
                    "srcdeps: SCM repository [{}] has been marked as built and up-to-date in this JVM. The artifact [{}] must be there in the local maven repository",
                    fetchId, artifact);
            return result;
        }

        String scmRepoId = null;

        try (PathLock projectBuildDir = buildDirectoriesManager.openBuildDirectory(scmRepo.getIdAsPath(), srcVersion)) {

            /* query the delegate again, because things may have changed since we requested the lock */
            final LocalArtifactResult result2 = delegate.find(session, request);
            final String version = artifact.getVersion();
            if (fetchLog.contains(fetchId)) {
                log.debug(
                        "srcdeps: SCM repository [{}] has been marked as built and up-to-date in this JVM. The artifact [{}] must be there in the local maven repository",
                        fetchId, artifact);
                return result2;
            } else {
                /* The repo has not been fetched in the current JVM yet */

                BuilderIo builderIo = scmRepo.getBuilderIo();
                IoRedirects ioRedirects = IoRedirects.builder() //
                        .stdin(IoRedirects.parseUri(builderIo.getStdin())) //
                        .stdout(IoRedirects.parseUri(builderIo.getStdout())) //
                        .stderr(IoRedirects.parseUri(builderIo.getStderr())) //
                        .build();

                List<String> buildArgs = enhanceBuildArguments(scmRepo.getBuildArguments(),
                        configurationProducer.getConfigurationLocation(),
                        delegate.getRepository().getBasedir().getAbsolutePath());
                scmRepoId = scmRepo.getId();
                BuildRequest buildRequest = BuildRequest.builder() //
                        .scmRepositoryId(scmRepo.getId()) //
                        .dependentProjectRootDirectory(configurationProducer.getMultimoduleProjectRootDirectory()) //
                        .projectRootDirectory(projectBuildDir.getPath()) //
                        .scmUrls(scmRepo.getUrls()) //
                        .srcVersion(srcVersion) //
                        .version(version) //
                        .buildArguments(buildArgs) //
                        .timeoutMs(scmRepo.getBuildTimeout().toMilliseconds()) //
                        .skipTests(scmRepo.isSkipTests()) //
                        .forwardProperties(configuration.getForwardProperties()) //
                        .addDefaultBuildArguments(scmRepo.isAddDefaultBuildArguments()) //
                        .verbosity(scmRepo.getVerbosity()) //
                        .ioRedirects(ioRedirects) //
                        .versionsMavenPluginVersion(scmRepo.getMaven().getVersionsMavenPluginVersion())
                        .gradleModelTransformer(scmRepo.getGradle().getModelTransformer()) //
                        .build();

                final String buildRequestHash = buildRequest.getHash();
                final String sourceTreeCommitId = scmService.checkout(buildRequest);
                log.info("srcdeps: Mapped artifact [{}] to revision [{}] via [{}]", artifact, sourceTreeCommitId, srcVersion);
                fetchLog.add(fetchId);

                final String pastCommitId = buildMetadataStore.retrieveCommitId(buildRequestHash);
                final Path localMavenRepoPath = delegate.getRepository().getBasedir().toPath();
                final GavSet gavSet = scmRepo.getGavSet();
                final GavSetWalker gavSetWalker = new GavSetWalker(localMavenRepoPath, gavSet, version);
                if (result2.isAvailable() && sourceTreeCommitId.equals(pastCommitId)) {

                    BuildMetadataStore.CheckSha1Consumer checkSha1Consumer = buildMetadataStore
                            .createCheckSha1Checker(buildRequestHash);
                    gavSetWalker.walk(checkSha1Consumer);
                    if (!checkSha1Consumer.isAnyArtifactChanged()) {
                        /*
                         * The artifact installed in the local Maven repo is the same as we built in the past hence
                         * there is no need to rebuild it
                         */
                        log.info(
                                "srcdeps: The artifact in the local Maven repo has not changed since we built it in the past: [{}]",
                                artifact);
                        return result2;
                    }
                }

                /* We need to rebuild from sources for whatever reason */
                log.debug("srcdeps: A rebuild of [{}] was triggered by [{}] lookup", fetchId, artifact);
                /* Uninstall all matching artifacts */
                uninstallGavSet(scmRepo, gavSetWalker);

                buildService.build(buildRequest);

                buildMetadataStore.storeCommitId(buildRequestHash, sourceTreeCommitId);
                BuildMetadataStore.StoreSha1Consumer gavtcPathConsumer = buildMetadataStore
                        .createStoreSha1Consumer(buildRequestHash);
                gavSetWalker.walk(gavtcPathConsumer);
                log.debug("srcdeps: Installed [{}] artifacts", gavtcPathConsumer.getCount());

                /* check once again if the delegate sees the newly built artifact */
                final LocalArtifactResult newResult = delegate.find(session, request);
                if (!newResult.isAvailable()) {
                    log.error(
                            "srcdeps: Build succeeded but the artifact {} is still not available in the local repository",
                            artifact);
                }
                return newResult;
            }

        } catch (BuildException | IOException e) {
            log.error("srcdeps: Could not build ["+ scmRepoId +"] using request [" + request + "]", e);
        }
        return result;
    }

    /**
     * In case the {@link #delegate} does not find the given artifact and the given artifact's version string is a
     * srcdeps version string, then the version is built from source and returned.
     *
     * @see org.eclipse.aether.repository.LocalRepositoryManager#find(org.eclipse.aether.RepositorySystemSession,
     *      org.eclipse.aether.repository.LocalArtifactRequest)
     */
    @Override
    public LocalArtifactResult find(RepositorySystemSession session, LocalArtifactRequest request) {
        Artifact artifact = request.getArtifact();
        log.trace("srcdeps: Looking up locally [{}]", artifact);
        final LocalArtifactResult result = delegate.find(session, request);

        final String version = artifact.getVersion();
        if (SrcVersion.isSrcVersion(version)) {
            /* A source dependency defined in pom.xml */
            final SrcVersion srcVersion = SrcVersion.parse(version);
            if (srcVersion.isImmutable() && result.isAvailable()) {
                /* Only tags and revisions do not need to get rebuilt once there in the local repo */
                log.debug("srcdeps: Found [{}] in the local maven repository; no need to rebuild", request.getArtifact());
                return result;
            }

            if (configuration.isSkip()) {
                log.debug("srcdeps: srcdeps is configured to be skipped");
            } else {
                final ScmRepository scmRepo = configurationQueryService
                        .findScmRepo(artifact.getGroupId(), artifact.getArtifactId(), version).assertSuccess()
                        .getRepository();

                /* Ensure that we fetch and build a branch just once per outer build */
                return buildDependency(artifact, scmRepo, result, srcVersion, session, request);
            }
        } else {
            final ScmRepositoryResult queryResult = configurationQueryService.findScmRepo(artifact.getGroupId(),
                    artifact.getArtifactId(), version);
            if (queryResult.getRepository() != null && queryResult.matchesBuildVersionPattern()) {
                /* A source dependency defined in srcdeps.yaml */
                if (configuration.isSkip()) {
                    log.debug("srcdeps: srcdeps is configured to be skipped");
                } else {
                    final ScmRepository scmRepo = queryResult.getRepository();
                    return buildDependency(artifact, scmRepo, result, scmRepo.getBuildRef(), session, request);
                }
            }
        }

        return result;
    }

    @Override
    public LocalMetadataResult find(RepositorySystemSession session, LocalMetadataRequest request) {
        return delegate.find(session, request);
    }

    @Override
    public String getPathForLocalArtifact(Artifact artifact) {
        return delegate.getPathForLocalArtifact(artifact);
    }

    @Override
    public String getPathForLocalMetadata(Metadata metadata) {
        return delegate.getPathForLocalMetadata(metadata);
    }

    @Override
    public String getPathForRemoteArtifact(Artifact artifact, RemoteRepository repository, String context) {
        return delegate.getPathForRemoteArtifact(artifact, repository, context);
    }

    @Override
    public String getPathForRemoteMetadata(Metadata metadata, RemoteRepository repository, String context) {
        return delegate.getPathForRemoteMetadata(metadata, repository, context);
    }

    @Override
    public LocalRepository getRepository() {
        return delegate.getRepository();
    }

}
