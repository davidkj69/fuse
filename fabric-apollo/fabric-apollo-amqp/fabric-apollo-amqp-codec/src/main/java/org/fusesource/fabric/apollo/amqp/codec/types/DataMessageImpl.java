/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.fabric.apollo.amqp.codec.types;

import org.fusesource.fabric.apollo.amqp.codec.api.DataMessage;
import org.fusesource.hawtbuf.Buffer;

import java.io.DataOutput;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class DataMessageImpl extends BareMessageImpl<List<Data>> implements DataMessage {

    public DataMessageImpl() {
        data = new ArrayList<Data>();
    }

    public DataMessageImpl(Buffer data) {
        this.data = new ArrayList<Data>();
        Data d = new Data();
        d.setValue(data);
        this.data.add(d);
    }

    @Override
    public long dataSize() {
        long rc = 0;
        for (Data d : data) {
            if (d != null) {
                rc += d.size();
            }
        }
        return rc;
    }

    @Override
    public void dataWrite(DataOutput out) throws Exception {
        if (data != null) {
            for (Data d : data) {
                if (d != null) {
                    d.write(out);
                }
            }
        }
    }

}