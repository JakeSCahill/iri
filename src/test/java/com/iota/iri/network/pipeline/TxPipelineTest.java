package com.iota.iri.network.pipeline;

import com.iota.iri.TransactionValidator;
import com.iota.iri.conf.NodeConfig;
import com.iota.iri.controllers.TipsViewModel;
import com.iota.iri.network.NeighborRouter;
import com.iota.iri.network.SampleTransaction;
import com.iota.iri.network.TransactionRequester;
import com.iota.iri.network.neighbor.Neighbor;
import com.iota.iri.service.milestone.LatestMilestoneTracker;
import com.iota.iri.service.snapshot.SnapshotProvider;
import com.iota.iri.storage.Tangle;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class TxPipelineTest {

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private Tangle tangle;

    @Mock
    private NeighborRouter neighborRouter;

    @Mock
    private NodeConfig nodeConfig;

    @Mock
    private TransactionValidator transactionValidator;

    @Mock
    private SnapshotProvider snapshotProvider;

    @Mock
    private TransactionRequester transactionRequester;

    @Mock
    private TipsViewModel tipsViewModel;

    @Mock
    private LatestMilestoneTracker latestMilestoneTracker;

    @Mock
    private Neighbor neighbor;

    @Mock
    private PreProcessStage preProcessStage;

    @Mock
    private ReceivedStage receivedStage;

    @Mock
    private ValidationStage validationStage;

    @Mock
    private ReplyStage replyStage;

    @Mock
    private BroadcastStage broadcastStage;

    @Mock
    private HashingStage hashingStage;

    @Mock
    private ProcessingContext hashingCtx;

    @Mock
    private HashingPayload hashingPayload;

    @Mock
    private ProcessingContext validationCtx;

    @Mock
    private ProcessingContext divergeToReplyAndReceivedCtx;

    @Mock
    private ProcessingContext<ReplyPayload> replyCtx;

    @Mock
    private ProcessingContext<ReceivedPayload> receivedCtx;

    @Mock
    private ProcessingContext broadcastCtx;

    @Mock
    private ProcessingContext abortCtx;

    private void mockHashingStage(TxPipeline pipeline) {
        Mockito.when(hashingPayload.getTxTrits()).thenReturn(null);
        Mockito.doAnswer(invocation -> {
            pipeline.getValidationStageQueue().put(validationCtx);
            return null;
        }).when(hashingStage).process(Mockito.any());
    }

    private void injectMockedStagesIntoPipeline(TxPipeline pipeline) {
        pipeline.setPreProcessStage(preProcessStage);
        pipeline.setReceivedStage(receivedStage);
        pipeline.setBroadcastStage(broadcastStage);
        pipeline.setHashingStage(hashingStage);
        pipeline.setReplyStage(replyStage);
        pipeline.setValidationStage(validationStage);
    }

    @Test
    public void processingAValidNewTransactionFlowsThroughTheEntirePipeline() throws InterruptedException {
        TxPipeline pipeline = new TxPipeline();
        pipeline.init(neighborRouter, nodeConfig, transactionValidator, tangle, snapshotProvider, transactionRequester,
                tipsViewModel, latestMilestoneTracker);

        // inject mocks
        injectMockedStagesIntoPipeline(pipeline);

        // mock after pre process context/stage
        Mockito.when(preProcessStage.process(Mockito.any())).thenReturn(hashingCtx);
        Mockito.when(hashingCtx.getNextStage()).thenReturn(TxPipeline.Stage.HASHING);
        Mockito.when(hashingCtx.getPayload()).thenReturn(hashingPayload);

        // mock hashing context/stage
        mockHashingStage(pipeline);

        // mock validation context/stage
        ImmutablePair<ProcessingContext<ReplyPayload>,
                ProcessingContext<ReceivedPayload>> divergePaypload = new ImmutablePair<>(replyCtx, receivedCtx);
        Mockito.when(validationStage.process(validationCtx)).thenReturn(divergeToReplyAndReceivedCtx);
        Mockito.when(divergeToReplyAndReceivedCtx.getNextStage()).thenReturn(TxPipeline.Stage.MULTIPLE);
        Mockito.when(divergeToReplyAndReceivedCtx.getPayload()).thenReturn(divergePaypload);

        // mock received
        Mockito.when(broadcastCtx.getNextStage()).thenReturn(TxPipeline.Stage.BROADCAST);
        Mockito.when(receivedStage.process(receivedCtx)).thenReturn(broadcastCtx);

        pipeline.start();

        // send in actual payload to kick off the 'processing'
        pipeline.process(neighbor, SampleTransaction.createSampleTxBuffer());

        // give it some time to 'process'
        Thread.sleep(100);

        // should have called
        Mockito.verify(preProcessStage).process(Mockito.any());
        Mockito.verify(hashingStage).process(Mockito.any());
        Mockito.verify(validationStage).process(Mockito.any());
        Mockito.verify(receivedStage).process(Mockito.any());
        Mockito.verify(replyStage).process(Mockito.any());
        Mockito.verify(broadcastStage).process(Mockito.any());
    }

    @Test
    public void processingAKnownTransactionOnlyFlowsToTheReplyStage() throws InterruptedException {
        TxPipeline pipeline = new TxPipeline();
        pipeline.init(neighborRouter, nodeConfig, transactionValidator, tangle, snapshotProvider, transactionRequester,
                tipsViewModel, latestMilestoneTracker);

        // inject mocks
        pipeline.setPreProcessStage(preProcessStage);
        pipeline.setReplyStage(replyStage);

        // mock after pre process context/stage
        Mockito.when(preProcessStage.process(Mockito.any())).thenReturn(replyCtx);
        Mockito.when(replyCtx.getNextStage()).thenReturn(TxPipeline.Stage.REPLY);

        pipeline.start();

        // send in actual payload to kick off the 'processing'
        pipeline.process(neighbor, SampleTransaction.createSampleTxBuffer());

        // give it some time to 'process'
        Thread.sleep(100);

        // should not have called
        Mockito.verify(hashingStage, Mockito.never()).process(Mockito.any());
        Mockito.verify(validationStage, Mockito.never()).process(Mockito.any());
        Mockito.verify(receivedStage, Mockito.never()).process(Mockito.any());
        Mockito.verify(broadcastStage, Mockito.never()).process(Mockito.any());

        // should have called
        Mockito.verify(replyStage).process(Mockito.any());
        Mockito.verify(preProcessStage).process(Mockito.any());
    }

    @Test
    public void processingAValidNewTransactionNotOriginatingFromANeighborFlowsThroughTheCorrectStages()
            throws InterruptedException {
        TxPipeline pipeline = new TxPipeline();
        pipeline.init(neighborRouter, nodeConfig, transactionValidator, tangle, snapshotProvider, transactionRequester,
                tipsViewModel, latestMilestoneTracker);

        // inject mocks
        injectMockedStagesIntoPipeline(pipeline);

        // mock after pre process context/stage
        Mockito.when(preProcessStage.process(Mockito.any())).thenReturn(hashingCtx);
        Mockito.when(hashingCtx.getNextStage()).thenReturn(TxPipeline.Stage.HASHING);
        Mockito.when(hashingCtx.getPayload()).thenReturn(hashingPayload);

        // mock hashing context/stage
        // mock hashing context/stage
        mockHashingStage(pipeline);

        // mock validation context/stage
        Mockito.when(validationStage.process(validationCtx)).thenReturn(receivedCtx);
        Mockito.when(receivedCtx.getNextStage()).thenReturn(TxPipeline.Stage.RECEIVED);

        // mock received
        Mockito.when(broadcastCtx.getNextStage()).thenReturn(TxPipeline.Stage.BROADCAST);
        Mockito.when(receivedStage.process(receivedCtx)).thenReturn(broadcastCtx);

        pipeline.start();

        // send into the pipeline but without originating from a neighbor
        pipeline.process(SampleTransaction.TRITS_OF_SAMPLE_TX);

        // give it some time to 'process'
        Thread.sleep(100);

        // should not have called
        Mockito.verify(replyStage, Mockito.never()).process(Mockito.any());
        // there's no pre processing because we already are supplying the tx trits
        Mockito.verify(preProcessStage, Mockito.never()).process(Mockito.any());

        // should have called
        Mockito.verify(hashingStage).process(Mockito.any());
        Mockito.verify(validationStage).process(Mockito.any());
        Mockito.verify(receivedStage).process(Mockito.any());
        Mockito.verify(broadcastStage).process(Mockito.any());
    }

    @Test
    public void anInvalidNewTransactionStopsBeingProcessedAfterTheValidationStage() throws InterruptedException {
        TxPipeline pipeline = new TxPipeline();
        pipeline.init(neighborRouter, nodeConfig, transactionValidator, tangle, snapshotProvider, transactionRequester,
                tipsViewModel, latestMilestoneTracker);

        // inject mocks
        injectMockedStagesIntoPipeline(pipeline);

        // mock after pre process context/stage
        Mockito.when(preProcessStage.process(Mockito.any())).thenReturn(hashingCtx);
        Mockito.when(hashingCtx.getNextStage()).thenReturn(TxPipeline.Stage.HASHING);
        Mockito.when(hashingCtx.getPayload()).thenReturn(hashingPayload);

        // mock hashing context/stage
        mockHashingStage(pipeline);

        // mock validation context/stage
        Mockito.when(validationStage.process(validationCtx)).thenReturn(abortCtx);
        Mockito.when(abortCtx.getNextStage()).thenReturn(TxPipeline.Stage.ABORT);

        pipeline.start();

        // send into the pipeline but without originating from a neighbor
        pipeline.process(SampleTransaction.TRITS_OF_SAMPLE_TX);

        // give it some time to 'process'
        Thread.sleep(100);

        // should not have called
        Mockito.verify(replyStage, Mockito.never()).process(Mockito.any());
        // there's no pre processing because we already are supplying the tx trits
        Mockito.verify(preProcessStage, Mockito.never()).process(Mockito.any());
        Mockito.verify(broadcastStage, Mockito.never()).process(Mockito.any());
        Mockito.verify(receivedStage, Mockito.never()).process(Mockito.any());

        // should have called
        Mockito.verify(hashingStage).process(Mockito.any());
        Mockito.verify(validationStage).process(Mockito.any());
    }

}