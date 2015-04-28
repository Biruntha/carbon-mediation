package org.wso2.carbon.inbound.endpoint.protocol.hl7;

import ca.uhn.hl7v2.HL7Exception;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.inbound.InboundProcessorParams;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.mediators.base.SequenceMediator;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
public class HL7Processor implements InboundResponseSender {
    private static final Log log = LogFactory.getLog(HL7Processor.class);

    // TODO: use axis2 worker pool here.
    private static ScheduledExecutorService executorService;

    private Map<String, Object> parameters;
    private InboundProcessorParams params;
    private String inSequence;
    private String onErrorSequence;
    private SynapseEnvironment synEnv;

    private boolean autoAck = true;
    private int timeOut;

    public HL7Processor(Map<String, Object> parameters) {
        executorService  = Executors.newScheduledThreadPool(50);
        this.parameters = parameters;

        params = (InboundProcessorParams) parameters.get(MLLPConstants.INBOUND_PARAMS);
        inSequence = params.getInjectingSeq();
        onErrorSequence = params.getOnErrorSeq();
        synEnv = params.getSynapseEnvironment();

        if (params.getProperties().getProperty(MLLPConstants.PARAM_HL7_AUTO_ACK).equals("false")) {
            autoAck = false;
        }

        timeOut = HL7MessageUtils.getInt(MLLPConstants.PARAM_HL7_TIMEOUT, params);

    }

    public void processRequest(final MLLPContext mllpContext) {
        mllpContext.setRequestTime(System.currentTimeMillis());

        // Prepare Synapse Context for message injection
        MessageContext synCtx = HL7MessageUtils.createSynapseMessageContext(mllpContext.getHl7Message(),
                params, synEnv);

        // If not AUTO ACK, we need response invocation through this processor
        if (!autoAck) {
            synCtx.setProperty(SynapseConstants.IS_INBOUND, true);
            synCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER, this);
            synCtx.setProperty(MLLPConstants.MLLP_CONTEXT, mllpContext);
        }

        SequenceMediator injectSeq = (SequenceMediator) synEnv.getSynapseConfiguration().getSequence(inSequence);
        injectSeq.setErrorHandler(onErrorSequence);

        Future timeoutFuture = null;

        if (!autoAck) {
            timeoutFuture = executorService.schedule(new TimeoutHandler(mllpContext), timeOut, TimeUnit.MILLISECONDS);
        }

        CallableTask task = new CallableTask(synCtx, injectSeq, synEnv, timeoutFuture);

        executorService.submit(task);

    }

    public Map<String, Object> getInboundParameterMap() {
        return parameters;
    }

    @Override
    public void sendBack(MessageContext messageContext) {
        MLLPContext mllpContext = (MLLPContext) messageContext.getProperty(MLLPConstants.MLLP_CONTEXT);
        sendBack(messageContext, mllpContext);
    }

    private void sendBack(MessageContext messageContext, MLLPContext mllpContext) {
        try {
            if ((((String) messageContext.getProperty(HL7Constants.HL7_RESULT_MODE)) != null) &&
                    ((String) messageContext.getProperty(HL7Constants.HL7_RESULT_MODE)).equals(HL7Constants.HL7_RESULT_MODE_NACK)) {
                String nackMessage = (String) messageContext.getProperty(HL7Constants.HL7_NACK_MESSAGE);
                mllpContext.setNackMode(true);
                mllpContext.setHl7Message(HL7MessageUtils.createNack(mllpContext.getHl7Message(), nackMessage));
            } else {
                mllpContext.setHl7Message(HL7MessageUtils.payloadToHL7Message(messageContext, params));
            }
        } catch (HL7Exception e) {
            log.error("Error while generating HL7 ACK response from payload.", e);
            handleException(mllpContext, "Error while generating ACK from payload.");
        }

        mllpContext.requestOutput();
    }

    public boolean isAutoAck() {
        return autoAck;
    }

    public int getTimeOut() {
        return timeOut;
    }

    public void setTimeOut(int timeOut) {
        this.timeOut = timeOut;
    }

    private void handleException(MLLPContext mllpContext, String msg) {
        try {
            mllpContext.setNackMode(true);
            mllpContext.setHl7Message(HL7MessageUtils.createNack(mllpContext.getHl7Message(), msg));
        } catch (HL7Exception e) {
            log.error("Error while generating NACK response.", e);
        }
    }
}

class TimeoutHandler implements Runnable {
    private static final Log log = LogFactory.getLog(TimeoutHandler.class);

    private MLLPContext context;

    public TimeoutHandler(MLLPContext context) {
        this.context = context;
    }

    public void run() {
        if (!context.hasResponded()) {
            try {
                log.warn("Timed out while waiting for HL7 Response to be generated.");
                context.setHl7Message(HL7MessageUtils.createNack(context.getHl7Message(),
                        "Timed out while waiting for HL7 Response to be generated."));
                context.setMarkForClose(true);
                context.requestOutput();
            } catch (HL7Exception e) {
                log.error("Could not generate timeout NACK response.", e);
            }
        }
    }
}
