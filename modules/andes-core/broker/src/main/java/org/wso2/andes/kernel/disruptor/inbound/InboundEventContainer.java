/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.andes.kernel.disruptor.inbound;

import com.lmax.disruptor.EventFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.wso2.andes.kernel.AndesAckEvent;
import org.wso2.andes.kernel.AndesChannel;
import org.wso2.andes.kernel.AndesException;
import org.wso2.andes.kernel.AndesMessage;
import org.wso2.andes.kernel.dtx.DtxBranch;

/**
 * All inbound events are published to disruptor ring buffer as an InboundEventContainer object
 * This class specifies the event type and any data that is relevant to that event.
 */
public class InboundEventContainer {

    private AndesChannel channel;

    /**
     * Specific event type of relevant to this InboundEventContainer
     */
    private Type eventType;

    /**
     * Andes state change related event
     */
    private AndesInboundStateEvent stateEvent;

    /**
     * For topic we may need to duplicate content.
     * {@link MessagePreProcessor} will do that.
     * And this list of messages will be written to DB.
     */
    private InboundMessageList messageList;

    /**
     * MessageId generated by {@link MessagePreProcessor} for slot submit a slot submit after a member left event.
     * This is to avoid lost slot submit events from left member nodes.
     */
    private long recoveryEventMessageId;

    /**
     * Acknowledgments received to disruptor
     */
    public AndesAckEvent ackData;

    /**
     * When content chunk processed this boolean is set to false
     * {@link ContentChunkHandler} will check this boolean and
     * if not processed will process the content chunk.
     */
    private final AtomicBoolean freshContent;

    /**
     * Publisher acknowledgements are handled by this ack handler
     */
    public PubAckHandler pubAckHandler;

    /**
     * Andes Transaction related event. Used to handle Andes transactions
     */
    private InboundTransactionEvent transactionEvent;

    /**
     * For storing retained messages for topic
     */
    public AndesMessage retainMessage;

    /**
     * Reference to the {@link DtxBranch} relating to a dtx event
     */
    private DtxBranch dtxBranch;

    /**
     * If an error occur processing the event. Error is stored in this variable
     */
    private Throwable error;

    /**
     * Event representing a message recovery request sent from a subscriber.
     */
    private InboundMessageRecoveryEvent recoverEvent;

    public void setDtxBranch(DtxBranch dtxBranch) {
        this.dtxBranch = dtxBranch;
    }

    /**
     * Getter for dtxBranch
     */
    public DtxBranch getDtxBranch() {
        return dtxBranch;
    }

    /**
     * Set the error occurred while processing the event using Disruptor event handler
     * @param error Throwable
     */
    public void setError(Throwable error) {
        this.error = error;
    }

    /**
     * Get the error occurred within the Disruptor event handler
     * @return Throwable
     */
    public Throwable getError() {
        return error;
    }

    /**
     * Return true if an error has occurred within a handler of disruptor
     * @return
     */
    public boolean hasErrorOccurred() {
        return error != null;
    }

    public void setMessageRecoveryEvent(InboundMessageRecoveryEvent recoveryEvent) {
        this.recoverEvent = recoveryEvent;
    }

    /**
     * Inbound event type is specified by this enum
     */
    public enum Type {

        /**
         * Message receive event
         */
        MESSAGE_EVENT,

        /**
         * Event related to adding a message to transaction
         */
        TRANSACTION_ENQUEUE_EVENT,

        /**
         * Event related to transaction commit
         */
        TRANSACTION_COMMIT_EVENT,

        /**
         * Event related to transaction rollback
         */
        TRANSACTION_ROLLBACK_EVENT,

        /**
         * Event related to a transaction close
         */
        TRANSACTION_CLOSE_EVENT,

        /**
         * Message acknowledgement receive event
         */
        ACKNOWLEDGEMENT_EVENT,

        /**
         * Andes state change related event type
         */
        STATE_CHANGE_EVENT,

        /**
         * Ignore the event and skip processing
         */
        IGNORE_EVENT,

        /**
         * Subscriber message recovery event. Creates upon a recovery request sent by a subscriber.
         */
        MESSAGE_RECOVERY_EVENT,

        /**
         * Distributed transaction prepare event
         */
        DTX_PREPARE_EVENT,

        /**
         * Distributed transaction commit event
         */
        DTX_COMMIT_EVENT,

        /**
         * Distributed transaction rollback event
         */
        DTX_ROLLBACK_EVENT,
    }

    /**
     * Instantiate the {@link org.wso2.andes.kernel.disruptor.inbound.InboundEventContainer} object
     */
    public InboundEventContainer() {
        setMessageList(new InboundMessageList());
        eventType = Type.IGNORE_EVENT;
        freshContent = new AtomicBoolean(true);

    }

    public final Type getEventType() {
        return eventType;
    }

    public final void setEventType(Type eventType) {
        this.eventType = eventType;
    }

    /**
     * Update the state of Andes core depending on the inbound event.
     * @throws AndesException
     */
    public void updateState() throws AndesException {
        switch (eventType) {
            case STATE_CHANGE_EVENT:
                stateEvent.updateState();
                break;
            case TRANSACTION_COMMIT_EVENT:
            case TRANSACTION_ROLLBACK_EVENT:
            case TRANSACTION_CLOSE_EVENT:
                getTransactionEvent().updateState();
                break;
            case DTX_PREPARE_EVENT:
            case DTX_COMMIT_EVENT:
            case DTX_ROLLBACK_EVENT:
                getDtxBranch().updateState(this);
                break;
            case MESSAGE_RECOVERY_EVENT:
                recoverEvent.updateState();
                break;
            default:
                break;
        }
    }

    public void setStateEvent(AndesInboundStateEvent stateEvent) {
        this.stateEvent = stateEvent;
    }

    /**
     * Reset internal references null and sets event type to IGNORE_EVENT
     */
    public void clear() {
        messageList.clear();
        retainMessage = null;
        ackData = null;
        stateEvent = null;
        eventType = Type.IGNORE_EVENT;
        pubAckHandler = null;
        setTransactionEvent(null);
        transactionEvent = null;
        freshContent.set(true);
        dtxBranch = null;
        error = null;
        recoverEvent = null;
    }

    /**
     * Disruptor uses this factory to populate the ring with inboundEvent objects
     */
    public static class InboundEventFactory implements EventFactory<InboundEventContainer> {

        @Override
        public InboundEventContainer newInstance() {
            return new InboundEventContainer() {
            };
        }
    }

    /**
     * If content is fresh and available for processing this will return true and atomically updated the state
     * of the event to denote content is taken for processing
     * <p/>
     * This method is used by {@link ContentChunkHandler} to
     * grab a incoming message for content processing
     *
     * @return true if content is fresh for processing and false otherwise
     */
    public boolean availableForContentProcessing() {
        return freshContent.compareAndSet(true, false);
    }

    /**
     * Add a message to the message list to be written to DB
     * @param message {@link org.wso2.andes.kernel.AndesMessage}
     */
    public void addMessage(AndesMessage message) {
        messageList.registerAddMessage(message, channel);
    }

    /**
     * Add a message to the message list with channel defined.
     * @param message {@link org.wso2.andes.kernel.AndesMessage}
     * @param channel andes channel
     */
    public void addMessage(AndesMessage message, AndesChannel channel) {
        messageList.registerAddMessage(message, channel);
    }


    /**
     * Human readable information of the event
     * @return brief description of the event
     */
    public String eventInfo() {
        if(eventType == Type.STATE_CHANGE_EVENT) {
            return stateEvent.eventInfo();
        } else {
            return eventType.toString();
        }
    }

    /**
     * Andes Transaction related event. Used to handle Andes transactions
     */
    public InboundTransactionEvent getTransactionEvent() {
        return transactionEvent;
    }

    /**
     * Set the transaction event relevant for publisher transactions
     * @param transactionEvent {@link org.wso2.andes.kernel.disruptor.inbound.InboundTransactionEvent}
     */
    public void setTransactionEvent(InboundTransactionEvent transactionEvent) {
        this.transactionEvent = transactionEvent;
    }

    /**
     * Returns the message list of which is written to DB by
     * {@link org.wso2.andes.kernel.disruptor.inbound.MessageWriter}
     * @return Message list
     */
    public List<AndesMessage> getMessageList() {
        return messageList.getMessageList();
    }

    /**
     * Return removed element at the specified position in the list.
     * @return andes message
     */
    public AndesMessage popMessage() {
        return messageList.registerPopMessage(0, channel);
    }

    /**
     * Set the message list to be written to the DB.
     * @param messageList messages list to be written to DB
     */
    public void setMessageList(InboundMessageList messageList) {
        this.messageList = messageList;
    }

    /**
     * Clear the inbound message list of the event.
     */
    public void clearMessageList(AndesChannel andesChannel) {
        messageList.registerClear(andesChannel);
    }

    public AndesChannel getChannel() {
        return channel;
    }

    public void setChannel(AndesChannel channel) {
        this.channel = channel;
    }

    public static EventFactory<InboundEventContainer> getFactory() {
        return new InboundEventFactory();
    }
}
