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
package org.fusesource.fabric.boot.commands;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.fusesource.fabric.boot.commands.support.EnsembleCommandSupport;
import org.fusesource.fabric.zookeeper.ZkDefs;

@Command(name = "create", scope = "fabric", description = "Creates a new fabric ensemble (ZooKeeper ensemble) and imports fabric profiles", detailedDescription = "classpath:create.txt")
public class Create extends EnsembleCommandSupport implements org.fusesource.fabric.boot.commands.service.Create {

    @Option(name = "--clean", description = "Clean local zookeeper cluster and configurations")
    private boolean clean;
    @Option(name = "--no-import", description = "Disable the import of the sample registry data")
    private boolean noImport;
    @Option(name = "--import-dir", description = "Directory of files to import into the newly created ensemble")
    private String importDir = getDefaultImportDir();
    @Option(name = "-v", aliases = {"--verbose"}, description = "Flag to enable verbose output of files being imported")
    boolean verbose = false;
    @Option(name = "-r", aliases = {"--resolver"}, description = "The global resolver policy, which becomes the default resolver policy applied to all new containers created in this fabric. Possible values are: localip, localhostname, publicip, publichostname, manualip. Default is localhostname.")
    String resolver;
    @Option(name = "-t", aliases = {"--time"}, description = "How long to wait (milliseconds) for the ensemble to start up before trying to import the default data")
    long ensembleStartupTime = 2000L;
    @Argument(required = false, multiValued = true, description = "List of containers. Empty list assumes current container only.")
    private List<String> containers;

    @Override
    protected Object doExecute() throws Exception {
        if (containers == null || containers.isEmpty()) {
            containers = Arrays.asList(System.getProperty("karaf.name"));
        }

        if (clean) {
            service.clean();
        }

        if (!noImport && importDir != null) {
            System.setProperty("profiles.auto.import.path",importDir);
        }

        if (resolver != null) {
            System.setProperty(ZkDefs.GLOBAL_RESOLVER_PROPERTY,resolver);
        }

        if (containers != null && !containers.isEmpty()) {
            service.createCluster(containers);
        }
        return null;
    }

    private static String getDefaultImportDir() {
        return System.getProperty("karaf.home", ".") + File.separatorChar + "fabric" + File.separatorChar + "import";
    }

    @Override
    public Object run() throws Exception {
        return doExecute();
    }

    @Override
    public boolean isClean() {
        return clean;
    }

    @Override
    public void setClean(boolean clean) {
        this.clean = clean;
    }

    @Override
    public boolean isNoImport() {
        return noImport;
    }

    @Override
    public void setNoImport(boolean noImport) {
        this.noImport = noImport;
    }

    @Override
    public String getImportDir() {
        return importDir;
    }

    @Override
    public void setImportDir(String importDir) {
        this.importDir = importDir;
    }

    @Override
    public boolean isVerbose() {
        return verbose;
    }

    @Override
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public long getEnsembleStartupTime() {
        return ensembleStartupTime;
    }

    @Override
    public void setEnsembleStartupTime(long ensembleStartupTime) {
        this.ensembleStartupTime = ensembleStartupTime;
    }

    @Override
    public List<String> getContainers() {
        return containers;
    }

    @Override
    public void setContainers(List<String> containers) {
        this.containers = containers;
    }
}
