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
package org.fusesource.fabric.commands;

import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.zookeeper.KeeperException;
import org.codehaus.jackson.map.ObjectMapper;
import org.fusesource.fabric.boot.commands.support.FabricCommand;
import org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Command(name = "cluster-list", scope = "fabric", description = "Lists all ActiveMQ message brokers in the fabric, enabling you to see which brokers are grouped into clusters.")
public class ClusterList extends FabricCommand {

    protected static String CLUSTER_PREFIX = "/fabric/registry/clusters";

    @Argument(required = false, description = "Path of the fabric registry node (Zookeeper registry node) to list. Relative paths are evaluated relative to the base node, /fabric/registry/clusters. If not specified, all clusters are listed.")
    String path = "";

    @Override
    protected Object doExecute() throws Exception {
        checkFabricAvailable();

        String realPath = path;
        if (!realPath.startsWith("/")) {
            realPath = CLUSTER_PREFIX;
            if (path.length() > 0) {
                realPath += "/" + path;
            }
        }
        printCluster(realPath, System.out);
        return null;
    }

    protected void printCluster(String dir, PrintStream out) throws InterruptedException, KeeperException, IOException, URISyntaxException {
        out.println(String.format("%-30s %-30s %-10s %s", "[cluster]", "[id]", "[master]", "[services]"));
        printChildren(dir, dir, out);
    }

    private void printChildren(String rootDir, String dir, PrintStream out) throws KeeperException, InterruptedException, IOException, URISyntaxException {
        // do we have any clusters at all?
        if (getZooKeeper().exists(dir) == null) {
            return;
        }

        List<String> children = getZooKeeper().getChildren(dir, false);
        boolean master = true;
        for (String child : children) {
            String childDir = dir + "/" + child;
            byte[] data = getZooKeeper().getData(childDir);
            if (data != null && data.length > 0) {
                String text = new String(data).trim();
                if (!text.isEmpty()) {
                    String clusterName = dir;
                    if (clusterName.startsWith(rootDir)) {
                        clusterName = clusterName.substring(rootDir.length());
                    }
                    if (clusterName.startsWith("/")) {
                        clusterName = clusterName.substring(1);
                    }
                    if (clusterName.length() == 0) {
                        clusterName = ".";
                    }
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> map = mapper.readValue(data, HashMap.class);

                    Object id = value(map, "id", "container");
                    Object services = value(map, "services");
                    if (services != null) {
                        // trim brackets
                        String serviceText = services.toString();
                        if (serviceText.startsWith("[") && serviceText.endsWith("]")) {
                            serviceText = serviceText.substring(1, serviceText.length() - 1);
                        }

                        services = ZooKeeperUtils.getSubstitutedData(getZooKeeper(), serviceText);
                    }

                    out.println(String.format("%-30s %-30s %-10s %s", clusterName, id, master, services));
                    master = false;
                }
            }
            printChildren(rootDir, childDir, out);
        }
    }

    protected Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

}
