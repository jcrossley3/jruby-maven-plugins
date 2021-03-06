/**
 * 
 */
package de.saumya.mojo.ruby.gems;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.repository.metadata.ArtifactRepositoryMetadata;
import org.apache.maven.artifact.repository.metadata.RepositoryMetadata;
import org.apache.maven.artifact.repository.metadata.RepositoryMetadataManager;
import org.apache.maven.artifact.repository.metadata.RepositoryMetadataResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.repository.legacy.metadata.DefaultMetadataResolutionRequest;
import org.apache.maven.repository.legacy.metadata.MetadataResolutionRequest;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.IOUtil;
import org.sonatype.aether.RepositorySystemSession;

@Component(role = GemManager.class)
public class DefaultGemManager implements GemManager {

    private static final String       DEFAULT_GEMS_REPOSITORY_BASE_URL = "http://gems.saumya.de/";

    @Requirement
    private RepositorySystem          repositorySystem;

    @Requirement
    private RepositoryMetadataManager repositoryMetadataManager;

    @Requirement
    private ProjectBuilder            builder;

    private Artifact setLatestVersionIfMissing(final Artifact artifact,
            final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories)
            throws GemException {
        if (artifact.getVersion() == null) {
            final List<String> versions = availableVersions(artifact,
                                                            localRepository,
                                                            remoteRepositories);
            artifact.setVersionRange(null);
            artifact.setVersion(versions.get(versions.size() - 1));
        }
        return artifact;
    }

    public Artifact createGemArtifact(final String gemname) throws GemException {
        return createGemArtifact(gemname, null);
    }

    public Artifact createGemArtifact(final String gemname, final String version)
            throws GemException {
        return createArtifact("rubygems", gemname, version, "gem");
    }

    public Artifact createGemArtifactWithLatestVersion(final String gemname,
            final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories)
            throws GemException {
        final Artifact gem = createGemArtifact(gemname, null);
        setLatestVersionIfMissing(gem, localRepository, remoteRepositories);
        return gem;
    }

    // gem repositories

    public ArtifactRepository defaultGemArtifactRepository() {
        return defaultGemArtifactRepositoryForVersion("0.0.0");
    }

    public ArtifactRepository defaultGemArtifactRepositoryForVersion(
            final String artifactVersion) {
        final String preRelease = artifactVersion != null
                && artifactVersion.matches(".*[a-z][A-Z].*") ? "pre" : "";
        return this.repositorySystem.createArtifactRepository("rubygems-"
                                                                      + preRelease
                                                                      + "releases",
                                                              DEFAULT_GEMS_REPOSITORY_BASE_URL
                                                                      + preRelease
                                                                      + "releases",
                                                              new DefaultRepositoryLayout(),
                                                              new ArtifactRepositoryPolicy(),
                                                              new ArtifactRepositoryPolicy());
    }

    public void addDefaultGemRepository(final List<ArtifactRepository> repos) {
        addDefaultGemRepositoryForVersion("0.0.0", repos);
    }

    public void addDefaultGemRepositoryForVersion(final String artifactVersion,
            final List<ArtifactRepository> repos) {
        final ArtifactRepository repo = defaultGemArtifactRepositoryForVersion(artifactVersion);
        for (final ArtifactRepository ar : repos) {
            if (ar.getUrl().equals(repo.getUrl())) {
                return;
            }
        }
        repos.add(repo);
    }

    // maven-gem artifacts
    public Artifact createJarArtifactForGemname(final String gemName)
            throws GemException {
        return createJarArtifactForGemname(gemName, null);
    }

    public Artifact createPomArtifactForGemname(final String gemName)
            throws GemException {
        final int index = gemName.lastIndexOf(GROUP_ID_ARTIFACT_ID_SEPARATOR);
        final String groupId = gemName.substring(0, index);
        final String artifactId = gemName.substring(index + 1);
        return createArtifact(groupId, artifactId, null, "pom");
    }

    public Artifact createJarArtifactForGemname(final String gemName,
            final String version) throws GemException {
        final int index = gemName.lastIndexOf(GROUP_ID_ARTIFACT_ID_SEPARATOR);
        final String groupId = gemName.substring(0, index);
        final String artifactId = gemName.substring(index + 1);
        return createArtifact(groupId, artifactId, version, "jar");
    }

    public Artifact createJarArtifactForGemnameWithLatestVersion(
            final String gemName, final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories)
            throws GemException {
        final Artifact artifact = createJarArtifactForGemname(gemName, null);
        setLatestVersionIfMissing(artifact, localRepository, remoteRepositories);
        return artifact;
    }

    // convenience methods

    public Artifact createArtifact(final String groupId,
            final String artifactId, final String version, final String type) {
        final Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setType(type);
        dep.setVersion(version == null ? "[0,)" : version);
        return this.repositorySystem.createDependencyArtifact(dep);
    }

    public void resolve(final Artifact artifact,
            final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories)
            throws GemException {
        if (artifact.getFile() == null || !artifact.getFile().exists()) {
            final Set<Artifact> artifacts = this.repositorySystem.resolve(new ArtifactResolutionRequest().setArtifact(artifact)
                    .setLocalRepository(localRepository)
                    .setRemoteRepositories(remoteRepositories))
                    .getArtifacts();
            if (artifacts.size() == 0) {
                throw new GemException("could not resolve artifact: "
                        + artifact);
            }
            artifact.setFile(artifacts.iterator().next().getFile());
        }
    }

    public MavenProject buildModel(final Artifact artifact,
            final RepositorySystemSession repositorySystemSession,
            final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories, boolean resolve)
            throws GemException {
        // build a POM and resolve all artifacts
        final ProjectBuildingRequest pomRequest = new DefaultProjectBuildingRequest()
                .setLocalRepository(localRepository)
                .setRemoteRepositories(remoteRepositories)
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_STRICT)
                .setRepositorySession(repositorySystemSession)
                .setResolveDependencies(resolve);
        try {

            return this.builder.build(artifact, pomRequest).getProject();

        }
        catch (final ProjectBuildingException e) {
            throw new GemException("error building POM",
                    e);
        }
    }

    public MavenProject buildPom(final Artifact artifact,
            final RepositorySystemSession repositorySystemSession,
            final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories)
            throws GemException {
        MavenProject pom = buildModel(artifact, repositorySystemSession, localRepository, remoteRepositories, true);

        resolve(pom.getArtifact(), localRepository, remoteRepositories);

        return pom;
    }

    // versions

    public String latestVersion(final Artifact artifact,
            final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories)
            throws GemException {
        final List<String> versions = availableVersions(artifact,
                                                        localRepository,
                                                        remoteRepositories);
        return versions.get(versions.size() - 1);
    }

    public List<String> availableVersions(final Artifact artifact,
            final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories)
            throws GemException {
        final MetadataResolutionRequest request = new DefaultMetadataResolutionRequest();
        request.setArtifact(artifact);
        request.setLocalRepository(localRepository);
        request.setRemoteRepositories(remoteRepositories);
        final RepositoryMetadata metadata = new ArtifactRepositoryMetadata(request.getArtifact());
        try {
            this.repositoryMetadataManager.resolve(metadata, request);

        }
        catch (final RepositoryMetadataResolutionException e) {
            throw new GemException("error updateding versions of artifact: "
                    + artifact, e);
        }
        final List<String> versions;
        if (metadata.getMetadata().getVersioning() == null) {
            if(remoteRepositories.size() == 0){
                throw new GemException("no version found - maybe system is offline or wrong <groupId:artifactId>: "
                    + artifact.getGroupId() + GROUP_ID_ARTIFACT_ID_SEPARATOR + artifact.getArtifactId());
            }
            versions = new ArrayList<String>();
        }
        else {
            versions= metadata.getMetadata()
                .getVersioning()
                .getVersions();
        }
        for(ArtifactRepository repo : remoteRepositories){
            BufferedReader reader = null;
            try {
                URL url = new URL(repo.getUrl() + "/" + artifact.getGroupId().replace(".", "/") + "/" + artifact.getArtifactId() + "/");
                reader = new BufferedReader(new InputStreamReader(url.openStream(),
                        "UTF-8"));
                String line = reader.readLine();
                while (line != null) {
                    //TODO maybe try to be more relax on how the version is embedded
                    if (line.contains("<a href=")) {
                        // first cut the end and then the beginning - allow greedy .*
                        String version = line.replaceFirst("</a>.*", "")
                            .replaceFirst(".*<a href=\".*\">", "");
                        if(version.endsWith("/")){
                            version = version.substring(0, version.length() - 1);
                            if (!versions.contains(version)) {
                                versions.add(version);
                            }
                        }
                    }
                    line = reader.readLine();
                }
            }
            catch (MalformedURLException e) {
                // should never happen
                throw new RuntimeException("error scraping versions from html page", e);
            }
            catch (IOException e) {
                // TODO 
//                System.err.println("error scraping versions from html index page: " + e.getMessage());
//                e.printStackTrace();
            }
            finally {
                IOUtil.close(reader);
            }
        }
        Collections.sort(versions);
        return versions;
    }
}
