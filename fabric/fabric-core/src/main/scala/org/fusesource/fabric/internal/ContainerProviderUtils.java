/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.fabric.internal;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.fusesource.fabric.api.CreateContainerMetadata;
import org.fusesource.fabric.api.CreateContainerOptions;
import org.fusesource.fabric.api.CreateJCloudsContainerOptions;
import org.fusesource.fabric.api.ZooKeeperClusterService;
import org.fusesource.fabric.utils.Base64Encoder;
import org.fusesource.fabric.utils.HostUtils;
import org.fusesource.fabric.utils.ObjectUtils;
import org.fusesource.fabric.zookeeper.ZkDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContainerProviderUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerProviderUtils.class);

    public static final String FAILURE_PREFIX = "Command Failed:";
    public static final String RESOLVER_OVERRIDE = "RESOLVER OVERRIDE:";


    public static final String ADDRESSES_PROPERTY_KEY = "addresses";
    private static final String LINE_APPEND = "sed  's/%s/&%s/' %s > %s";
    private static final String FIRST_FABRIC_DIRECTORY = "ls -l | grep fuse-fabric | grep ^d | awk '{ print $NF }' | sort -n | head -1";

    private static final String RUN_FUNCTION = loadFunction("run.sh");
    private static final String DOWNLOAD_FUNCTION = loadFunction("download.sh");
    private static final String MAVEN_DOWNLOAD_FUNCTION = loadFunction("maven-download.sh");
    private static final String INSTALL_JDK = loadFunction("install-open-jdk.sh");
    private static final String INSTALL_CURL = loadFunction("install-curl.sh");
    private static final String UPDATE_PKGS = loadFunction("update-pkgs.sh");
    private static final String VALIDATE_REQUIREMENTS = loadFunction("validate-requirements.sh");
    private static final String EXIT_IF_NOT_EXISTS = loadFunction("exit-if-not-exists.sh");
    private static final String COPY_NODE_METADATA = loadFunction("copy-node-metadata.sh");
    private static final String KARAF_CHECK = loadFunction("karaf-check.sh");
    private static final String REPLACE_IN_FILE = loadFunction("replace-in-file.sh");
    private static final String CONFIGURE_HOSTNAMES = loadFunction("configure-hostname.sh");

    public static final int DEFAULT_SSH_PORT = 8101;

    private static final String[] FALLBACK_REPOS = {"http://repo.fusesource.com/nexus/content/groups/public/", "http://repo.fusesource.com/nexus/content/groups/ea/", "http://repo.fusesource.com/nexus/content/repositories/snapshots/"};

    private ContainerProviderUtils() {
        //Utility Class
    }

    /**
     * Creates a shell script for installing and starting up a container.
     *
     * @param options
     * @return
     * @throws MalformedURLException
     */
    public static String buildInstallAndStartScript(CreateContainerOptions options) throws MalformedURLException, URISyntaxException {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash").append("\n");
        sb.append(RUN_FUNCTION).append("\n");
        sb.append(DOWNLOAD_FUNCTION).append("\n");
        sb.append(MAVEN_DOWNLOAD_FUNCTION).append("\n");
        sb.append(UPDATE_PKGS).append("\n");
        sb.append(INSTALL_CURL).append("\n");
        sb.append(INSTALL_JDK).append("\n");
        sb.append(VALIDATE_REQUIREMENTS).append("\n");
        sb.append(EXIT_IF_NOT_EXISTS).append("\n");
        sb.append(COPY_NODE_METADATA).append("\n");
        sb.append(KARAF_CHECK).append("\n");
        sb.append(REPLACE_IN_FILE).append("\n");
        sb.append(CONFIGURE_HOSTNAMES).append("\n");
        sb.append("run mkdir -p ~/containers/ ").append("\n");
        sb.append("run cd ~/containers/ ").append("\n");
        sb.append("run mkdir -p ").append(options.getName()).append("\n");
        sb.append("run cd ").append(options.getName()).append("\n");
        //We need admin access to be able to install curl & java.
        if (options.isAdminAccess()) {
            //This is not really needed.
            //Its just here as a silly workaround for some cases which fail to get the first thing installed.
            sb.append("update-pkgs").append("\n");
            sb.append("install-openjdk").append("\n");
            sb.append("install-curl").append("\n");
        }
        sb.append("validate-requirements").append("\n");
        extractTargzIntoDirectory(sb, options.getProxyUri(), "org.fusesource.fabric", "fuse-fabric", FabricConstants.FABRIC_VERSION);
        sb.append("run cd `").append(FIRST_FABRIC_DIRECTORY).append("`\n");
        List<String> lines = new ArrayList<String>();
        lines.add(ZkDefs.GLOBAL_RESOLVER_PROPERTY + "=" + options.getResolver());
        appendFile(sb, "etc/system.properties", lines);
        replaceLineInFile(sb, "etc/system.properties", "karaf.name=root", "karaf.name=" + options.getName());
        replaceLineInFile(sb, "etc/org.apache.karaf.shell.cfg", "sshPort=8101", "sshPort=" + DEFAULT_SSH_PORT);
        appendFile(sb, "etc/system.properties", Arrays.asList("\n"));

        //Read all system properties
        for (Map.Entry<String, Properties> entry : options.getSystemProperties().entrySet()) {
            Properties sysprops = entry.getValue();
            for (Object type : sysprops.keySet()) {
                Object value = sysprops.get(type);
                appendFile(sb, "etc/system.properties", Arrays.asList(type + "=" + value));
            }
        }

        //TODO: Be simple & move all of the code below under system properties MAP.
        if (options.getPreferredAddress() != null) {
            appendFile(sb, "etc/system.properties", Arrays.asList(HostUtils.PREFERED_ADDRESS_PROPERTY_NAME + "=" + options.getPreferredAddress()));
        }

        if (options.isEnsembleServer()) {
            appendFile(sb, "etc/system.properties", Arrays.asList(ZooKeeperClusterService.ENSEMBLE_AUTOSTART + "=true"));
            appendFile(sb, "etc/system.properties", Arrays.asList(ZooKeeperClusterService.PROFILES_AUTOIMPORT_PATH + "=${karaf.home}/fabric/import/"));
        } else if (options.getZookeeperUrl() != null) {
            appendFile(sb, "etc/system.properties", Arrays.asList("zookeeper.url = " + options.getZookeeperUrl()));
        }

        appendToLineInFile(sb, "etc/org.apache.karaf.features.cfg", "featuresBoot=", "fabric-agent,");
        //Add the proxyURI to the list of repositories
        if (options.getProxyUri() != null) {
            appendToLineInFile(sb, "etc/org.ops4j.pax.url.mvn.cfg", "repositories=", options.getProxyUri().toString() + ",");
        }
        //Just for ensemble servers we want to copy their creation metadata for import.
        if (options.isEnsembleServer()) {
            CreateContainerMetadata metadata = options.getMetadataMap().get(options.getName());
            if (metadata != null) {
                byte[] metadataPayload = ObjectUtils.toBytes(metadata);
                if (metadataPayload != null && metadataPayload.length > 0) {
                    sb.append("copy-node-metadata ").append(options.getName()).append(" ").append(new String(Base64Encoder.encode(metadataPayload))).append("\n");
                }
            }
        }
        if (options instanceof CreateJCloudsContainerOptions) {
            sb.append("configure-hostnames").append(" ").append(((CreateJCloudsContainerOptions)options).getProviderName()).append("\n");
        }
        sb.append("run nohup bin/start").append("\n");
        sb.append("karaf-check `pwd`").append("\n");
        return sb.toString();
    }


    /**
     * Creates a shell script for starting an existing remote container.
     *
     * @param options
     * @return
     * @throws MalformedURLException
     */
    public static String buildStartScript(CreateContainerOptions options) throws MalformedURLException {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash").append("\n");
        sb.append(RUN_FUNCTION).append("\n");
        sb.append(KARAF_CHECK).append("\n");
        sb.append(CONFIGURE_HOSTNAMES).append("\n");
        sb.append("run cd ~/containers/ ").append("\n");
        sb.append("run cd ").append(options.getName()).append("\n");
        sb.append("run cd `").append(FIRST_FABRIC_DIRECTORY).append("`\n");
        if (options instanceof CreateJCloudsContainerOptions) {
            sb.append("configure-hostnames").append(" ").append(((CreateJCloudsContainerOptions)options).getProviderName()).append("\n");
        }
        sb.append("run nohup bin/start").append("\n");
        sb.append("karaf-check `pwd`").append("\n");
        return sb.toString();
    }

    /**
     * Creates a shell script for stopping a container.
     *
     * @param options
     * @return
     * @throws MalformedURLException
     */
    public static String buildStopScript(CreateContainerOptions options) throws MalformedURLException {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash").append("\n");
        sb.append(RUN_FUNCTION).append("\n");
        sb.append("run cd ~/containers/ ").append("\n");
        sb.append("run cd ").append(options.getName()).append("\n");
        sb.append("run cd `").append(FIRST_FABRIC_DIRECTORY).append("`\n");
        sb.append("run bin/stop").append("\n");
        return sb.toString();
    }

    /**
     * Creates a shell script for uninstalling a container.
     *
     * @param options
     * @return
     * @throws MalformedURLException
     */
    public static String buildUninstallScript(CreateContainerOptions options) throws MalformedURLException {
        StringBuilder sb = new StringBuilder();
        sb.append(RUN_FUNCTION).append("\n");
        sb.append("run cd ~/containers/ ").append("\n");
        sb.append("run rm -rf ").append(options.getName()).append("\n");
        return sb.toString();
    }


    private static void replaceLineInFile(StringBuilder sb, String path, String pattern, String line) {
        sb.append("replace-in-file ")
                .append("\"").append(pattern).append("\" ")
                .append("\"").append(line).append("\" ")
                .append(path)
                .append("\n");
    }

    private static void appendToLineInFile(StringBuilder sb, String path, String pattern, String line) {
        sb.append(String.format(LINE_APPEND, pattern.replaceAll("/", "\\\\/"), line.replaceAll("/", "\\\\/"), path, path + ".tmp")).append("\n");
        sb.append("mv " + path + ".tmp " + path).append("\n");
    }

    private static void appendFile(StringBuilder sb, String path, Iterable<String> lines) {
        final String MARKER = "END_OF_FILE";
        sb.append("cat >> ").append(path).append(" <<'").append(MARKER).append("'\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        sb.append(MARKER).append("\n");
    }

    private static void extractTargzIntoDirectory(StringBuilder sb, URI proxy, String groupId, String artifactId, String version) throws URISyntaxException {

        String file = artifactId + "-" + version + ".tar.gz";
        String directory = groupId.replaceAll("\\.", "/") + "/" + artifactId + "/" + version + "/";

        //TODO: There may be cases where this is not good enough
        if (proxy != null) {
            String baseProxyURL = (!proxy.toString().endsWith("/")) ? proxy.toString() + "/" : proxy.toString();


            sb.append("maven-download ").append(baseProxyURL).append(" ")
                    .append(groupId).append(" ")
                    .append(artifactId).append(" ")
                    .append(version).append(" ")
                    .append("tar.gz").append("\n");
        }

        for (String fallbackRepo : FALLBACK_REPOS) {
            sb.append("if [ ! -f " + file + " ] ; then ").append("maven-download ").append(fallbackRepo).append(" ")
                    .append(groupId).append(" ")
                    .append(artifactId).append(" ")
                    .append(version).append(" ")
                    .append("tar.gz").append(" ; fi \n");
        }
        sb.append("exit-if-not-exists ").append(file).append("\n");
        sb.append("run tar -xpzf ").append(file).append("\n");
    }

    private static String loadFunction(String function) {
        InputStream is = ContainerProviderUtils.class.getResourceAsStream(function);
        InputStreamReader reader = null;
        BufferedReader bufferedReader = null;
        StringBuilder sb = new StringBuilder();

        try {
            reader = new InputStreamReader(is, "UTF-8");
            bufferedReader = new BufferedReader(reader);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Throwable e) {
        } finally {
            try {
                if (reader != null)
                    reader.close();
            } catch (Throwable e) {
            }
            try {
                if (bufferedReader != null)
                    bufferedReader.close();
            } catch (Throwable e) {
            }
            try {
                is.close();
            } catch (Throwable e) {
            }

        }
        return sb.toString();
    }

    /**
     * Parses the script failure message and isolates the failure cause.
     *
     * @param output
     * @return
     */
    public static String parseScriptFailure(String output) {
        String error = "";
        if (output != null) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.contains(FAILURE_PREFIX)) {
                    try {
                        error = line.substring(line.lastIndexOf(FAILURE_PREFIX) + FAILURE_PREFIX.length());
                    } catch (Throwable t) {
                        //noop
                        error = "Unknown error";
                    }
                    return error;
                }
            }
        }
        return error;
    }

    public static String parseResolverOverride(String output)  {
        String resolver = null;
        if (output != null) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.contains(RESOLVER_OVERRIDE)) {
                    try {
                        resolver = line.substring(line.lastIndexOf(RESOLVER_OVERRIDE) + RESOLVER_OVERRIDE.length());
                        return resolver.trim();
                    } catch (Throwable t) {
                        //noop
                    }
                }
            }
        }
        return resolver;
    }
}