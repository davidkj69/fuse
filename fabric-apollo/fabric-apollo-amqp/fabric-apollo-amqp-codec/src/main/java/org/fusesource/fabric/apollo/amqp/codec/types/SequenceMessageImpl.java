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

import org.fusesource.fabric.apollo.amqp.codec.api.SequenceMessage;
import org.fusesource.fabric.apollo.amqp.codec.types.AmqpSequence;
import org.fusesource.fabric.apollo.amqp.codec.types.BareMessageImpl;

import java.io.DataOutput;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class SequenceMessageImpl extends BareMessageImpl<List<AmqpSequence>> implements SequenceMessage {

    public SequenceMessageImpl() {
        data = new ArrayList<AmqpSequence>();
    }

    public long dataSize() {
        long rc = 0;
        for (AmqpSequence s : data) {
            if (s != null) {
                rc += s.size();
            }
        }
        return rc;
    }

    @Override
    public void dataWrite(DataOutput out) throws Exception {
        for (AmqpSequence s : data) {
            if (s != null) {
                s.write(out);
            }
        }
    }

}