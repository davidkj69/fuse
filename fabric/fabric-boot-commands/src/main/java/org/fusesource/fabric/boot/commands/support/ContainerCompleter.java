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
package org.fusesource.fabric.boot.commands.support;

import java.util.List;

import org.apache.karaf.shell.console.Completer;
import org.apache.karaf.shell.console.completer.StringsCompleter;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.FabricService;
import org.linkedin.zookeeper.client.IZKClient;

public class ContainerCompleter implements Completer {

    protected FabricService fabricService;
    protected IZKClient zooKeeper;

    @Override
    public int complete(String buffer, int cursor, List<String> candidates) {
        if (zooKeeper.isConnected()) {
            StringsCompleter delegate = new StringsCompleter();
            for (Container container : fabricService.getContainers()) {
                if (apply(container)) {
                    delegate.getStrings().add(container.getId());
                }
            }
            return delegate.complete(buffer, cursor, candidates);
        } else {
            return 0;
        }
    }

    public boolean apply(Container container) {
        return true;
    }

    public FabricService getFabricService() {
        return fabricService;
    }

    public void setFabricService(FabricService fabricService) {
        this.fabricService = fabricService;
    }

    public IZKClient getZooKeeper() {
        return zooKeeper;
    }

    public void setZooKeeper(IZKClient zooKeeper) {
        this.zooKeeper = zooKeeper;
    }
}
