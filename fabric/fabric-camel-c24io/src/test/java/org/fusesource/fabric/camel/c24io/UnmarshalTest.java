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
package org.fusesource.fabric.camel.c24io;

import java.util.List;

import biz.c24.io.api.data.ComplexDataObject;
import biz.c24.testtransactions.Transactions;

import org.apache.camel.test.CamelTestSupport;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.dataformat.C24IOContentType;

/**
 * @version $Revision$
 */
public class UnmarshalTest extends CamelTestSupport {
    public void testParsingMessage() throws Exception {
        MockEndpoint resultEndpoint = resolveMandatoryEndpoint("mock:result", MockEndpoint.class);
        resultEndpoint.expectedMessageCount(1);

        resultEndpoint.assertIsSatisfied();

        List<Exchange> list = resultEndpoint.getReceivedExchanges();
        Exchange exchange = list.get(0);
        Message in = exchange.getIn();
        ComplexDataObject object = assertIsInstanceOf(ComplexDataObject.class, in.getBody());
        log.info("Received: " + object);
    }

    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {

                from("file:src/test/data?noop=true").
                        unmarshal().c24io(Transactions.class).
                        to("mock:result");
            }
        };
    }
}