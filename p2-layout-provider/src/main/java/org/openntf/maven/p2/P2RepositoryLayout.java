/**
 * Copyright © 2019 Jesse Gallagher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openntf.maven.p2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibm.commons.util.PathUtil;
import com.ibm.commons.xml.DOMUtil;
import com.ibm.commons.xml.Format;
import com.ibm.commons.xml.XMLException;

public class P2RepositoryLayout implements RepositoryLayout {
	private static final Logger log = P2RepositoryLayoutProvider.log;

	private String id;
	private String url;
	private Path metadataScratch;
	private Document artifactsJar;
	private boolean checkedArtifacts;

	public P2RepositoryLayout(String id, String url) throws IOException {
		this.id = id;
		this.url = url;
		this.metadataScratch = Files.createTempDirectory(getClass().getName() + "-metadata");
	}

	@Override
	public URI getLocation(Artifact artifact, boolean upload) {
		if(log.isLoggable(Level.INFO)) {
			log.info("getLocation for artifact " + artifact);
		}
		
		switch(String.valueOf(artifact.getExtension())) {
		case "pom": { //$NON-NLS-1$
			// TODO create virtual pom
			Path pomOut = this.metadataScratch.resolve(artifact.getArtifactId() + "-" + artifact.getVersion() + ".pom"); //$NON-NLS-1$ //$NON-NLS-2$
			if(!Files.exists(pomOut) && this.id.equals(artifact.getGroupId())) {
				// Check if it exists in the artifacts.jar
				Document artifactsJar = getArtifactsJar();
				if(artifactsJar != null) {
					try {
						Element artifactNode = (Element)DOMUtil.node(artifactsJar, "/repository/artifacts/artifact[@id=\"" + artifact.getArtifactId() + "\"]"); //$NON-NLS-1$ //$NON-NLS-2$
						if(artifactNode != null) {
							// Then it's safe to make a file for it
							Document xml = DOMUtil.createDocument();
							Element project = DOMUtil.createElement(xml, "project"); //$NON-NLS-1$
							DOMUtil.createElement(xml, project, "modelVersion").setTextContent("4.0.0"); //$NON-NLS-1$ //$NON-NLS-2$
							DOMUtil.createElement(xml, project, "groupId").setTextContent(artifact.getGroupId()); //$NON-NLS-1$
							DOMUtil.createElement(xml, project, "artifactId").setTextContent(artifact.getArtifactId()); //$NON-NLS-1$
							DOMUtil.createElement(xml, project, "name").setTextContent(artifact.getArtifactId()); //$NON-NLS-1$
							DOMUtil.createElement(xml, project, "version").setTextContent(artifact.getVersion()); //$NON-NLS-1$
							try(OutputStream os = Files.newOutputStream(pomOut, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
								DOMUtil.serialize(os, xml, Format.defaultFormat);
							}
						}
					} catch(XMLException | IOException e) {
						throw new RuntimeException(e);
					}
				}
			}
			return pomOut.toUri();
		}
		case "jar": { //$NON-NLS-1$
			String jar = artifact.getArtifactId() + "_" + artifact.getVersion() + ".jar"; //$NON-NLS-1$ //$NON-NLS-2$
			String url = PathUtil.concat(this.url, "plugins/" + jar, '/'); //$NON-NLS-1$
			return URI.create(url);
		}
		}
		
		return null;
	}

	@Override
	public URI getLocation(Metadata metadata, boolean upload) {
		if(log.isLoggable(Level.INFO)) {
			log.info("getLocation for metadata " + metadata);
		}
		
		Path metadataOut = this.metadataScratch.resolve("maven-metadata-" + metadata.getArtifactId() + ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
		if(!Files.exists(metadataOut) && this.id.equals(metadata.getGroupId())) {
			Document xml = getArtifactsJar();
			if(xml != null) {
				// Create a temporary maven-metadata.xml
				try {
					Element artifact = (Element)DOMUtil.node(xml, "/repository/artifacts/artifact[@id=\"" + metadata.getArtifactId() + "\"]"); //$NON-NLS-1$ //$NON-NLS-2$
					if(artifact != null) {
						System.out.println("creating metadata for version " + artifact + " at " + metadataOut);
						String version = artifact.getAttribute("version"); //$NON-NLS-1$
						
						Document result = DOMUtil.createDocument();
						Element metadataNode = DOMUtil.createElement(result, "metadata"); //$NON-NLS-1$
						DOMUtil.createElement(result, metadataNode, "groupId").setTextContent(this.id); //$NON-NLS-1$
						DOMUtil.createElement(result, metadataNode, "artifactId").setTextContent(metadata.getArtifactId()); //$NON-NLS-1$
						Element versioning = DOMUtil.createElement(result, metadataNode, "versioning"); //$NON-NLS-1$
						DOMUtil.createElement(result, versioning, "latest").setTextContent(version); //$NON-NLS-1$
						DOMUtil.createElement(result, versioning, "release").setTextContent(version); //$NON-NLS-1$
						Element versions = DOMUtil.createElement(result, versioning, "versions"); //$NON-NLS-1$
						DOMUtil.createElement(result, versions, "version").setTextContent(version); //$NON-NLS-1$
						
						try(OutputStream os = Files.newOutputStream(metadataOut, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
							DOMUtil.serialize(os, xml, Format.defaultFormat);
						}
					}
				} catch(IOException | XMLException e) {
					throw new RuntimeException(e);
				}
			}
		}
		
		return metadataOut.toUri();
	}

	@Override
	public List<Checksum> getChecksums(Artifact artifact, boolean upload, URI location) {
		return Collections.emptyList();
	}

	@Override
	public List<Checksum> getChecksums(Metadata metadata, boolean upload, URI location) {
		return Collections.emptyList();
	}
	
	private synchronized Document getArtifactsJar() {
		if(!checkedArtifacts) {
			this.checkedArtifacts = true;
			if(this.artifactsJar == null) {
				URI artifactsJar = URI.create(PathUtil.concat(this.url, "artifacts.jar", '/')); //$NON-NLS-1$
				
				try {
					Document xml;
					try(InputStream is = artifactsJar.toURL().openStream()) {
						try(JarInputStream jis = new JarInputStream(is)) {
							jis.getNextEntry();
							xml = DOMUtil.createDocument(jis);
						}
					}
					this.artifactsJar = xml;
				} catch(IOException | XMLException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return this.artifactsJar;
	}

}