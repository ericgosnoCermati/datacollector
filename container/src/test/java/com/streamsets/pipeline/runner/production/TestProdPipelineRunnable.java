/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.runner.production;

import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.errorrecordstore.impl.FileErrorRecordStore;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.config.DeliveryGuarantee;
import com.streamsets.pipeline.runner.MockStages;
import com.streamsets.pipeline.runner.PipelineRuntimeException;
import com.streamsets.pipeline.runner.SourceOffsetTracker;
import com.streamsets.pipeline.prodmanager.ProductionPipelineManagerTask;
import com.streamsets.pipeline.prodmanager.State;
import com.streamsets.pipeline.snapshotstore.SnapshotStatus;
import com.streamsets.pipeline.snapshotstore.impl.FileSnapshotStore;
import com.streamsets.pipeline.stagelibrary.StageLibraryTask;
import com.streamsets.pipeline.store.impl.FilePipelineStoreTask;
import com.streamsets.pipeline.util.Configuration;
import com.streamsets.pipeline.util.TestUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public class TestProdPipelineRunnable {

  private static final String PIPELINE_NAME = "xyz";
  private static final String REVISION = "0";
  private ProductionPipelineManagerTask manager = null;

  @BeforeClass
  public static void beforeClass() {
    System.setProperty("pipeline.data.dir", "./target/var");
  }

  @AfterClass
  public static void afterClass() throws IOException {
    System.getProperties().remove("pipeline.data.dir");
  }

  @Before()
  public void setUp() {
    MockStages.resetStageCaptures();
    RuntimeInfo info = new RuntimeInfo(Arrays.asList(getClass().getClassLoader()));
    manager = new ProductionPipelineManagerTask(info, Mockito.mock(Configuration.class)
        , Mockito.mock(FilePipelineStoreTask.class), Mockito.mock(StageLibraryTask.class));
    manager.init();
  }

  @After
  public void tearDown() {
    manager.stop();
    manager.getStateTracker().getStateFile().delete();
  }

  @Test
  public void testRun() throws PipelineRuntimeException {

    TestUtil.captureMockStages();

    ProductionPipeline pipeline = createProductionPipeline(DeliveryGuarantee.AT_MOST_ONCE, true);
    ProductionPipelineRunnable runnable = new ProductionPipelineRunnable(manager, pipeline, "xyz", "1.0");
    runnable.run();

    //The source returns null offset because all the data from source was read
    Assert.assertNull(pipeline.getCommittedOffset());

    Assert.assertTrue(pipeline.getPipeline().getRunner().getBatchesOutput().isEmpty());
  }

  @Test
  public void testStop() throws PipelineRuntimeException {

    TestUtil.captureMockStages();

    ProductionPipeline pipeline = createProductionPipeline(DeliveryGuarantee.AT_MOST_ONCE, false);
    ProductionPipelineRunnable runnable = new ProductionPipelineRunnable(manager, pipeline, "xyz", "1.0");

    runnable.stop();
    Assert.assertTrue(pipeline.wasStopped());

    //Stops after the first batch
    runnable.run();

    //Offset 1 expected as pipeline was stopped after the first batch
    Assert.assertEquals("1", pipeline.getCommittedOffset());
    //no output as capture was not set to true
    Assert.assertTrue(pipeline.getPipeline().getRunner().getBatchesOutput().isEmpty());
  }

  private volatile CountDownLatch latch;
  private volatile boolean stopInterrupted;

  @Test
  public void testStopInterrupt() throws Exception {
    latch = new CountDownLatch(1);
    stopInterrupted = false;
    MockStages.setSourceCapture(new BaseSource() {
      @Override
      public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
        try {
          latch.countDown();
          Thread.currentThread().sleep(1000000);
        } catch (InterruptedException ex) {
          stopInterrupted = true;
        }
        return null;
      }
    });

    ProductionPipeline pipeline = createProductionPipeline(DeliveryGuarantee.AT_MOST_ONCE, false);
    ProductionPipelineRunnable runnable = new ProductionPipelineRunnable(manager, pipeline, "xyz", "1.0");

    Thread t = new Thread(runnable);
    t.start();
    latch.await();
    runnable.stop();
    t.join();
    Assert.assertTrue(stopInterrupted);
  }

  @Test
  public void testErrorState() throws PipelineRuntimeException {
    System.setProperty("pipeline.data.dir", "./target/var");

    MockStages.setSourceCapture(new BaseSource() {
      @Override
      public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
        throw new RuntimeException("Simulate runtime failure in source");
      }
    });

    ProductionPipeline pipeline = createProductionPipeline(DeliveryGuarantee.AT_MOST_ONCE, true);
    ProductionPipelineRunnable runnable = new ProductionPipelineRunnable(manager, pipeline, PIPELINE_NAME, "1.0");

    //Stops after the first batch
    runnable.run();

    Assert.assertEquals(State.ERROR, manager.getPipelineState().getState());
    //Offset 1 expected as there was a Runtime exception
    Assert.assertEquals("1", pipeline.getCommittedOffset());
    //no output as captured as there was an exception in source
    Assert.assertTrue(pipeline.getPipeline().getRunner().getBatchesOutput().isEmpty());
  }

  private ProductionPipeline createProductionPipeline(DeliveryGuarantee deliveryGuarantee,
                                                                      boolean capturenextBatch) throws PipelineRuntimeException {
    SourceOffsetTracker tracker = new TestUtil.SourceOffsetTrackerImpl("1");
    FileSnapshotStore snapshotStore = Mockito.mock(FileSnapshotStore.class);
    FileErrorRecordStore fileErrorRecordStore = Mockito.mock(FileErrorRecordStore.class);

    Mockito.when(snapshotStore.getSnapshotStatus(PIPELINE_NAME)).thenReturn(new SnapshotStatus(false, false));
    ProductionPipelineRunner runner = new ProductionPipelineRunner(snapshotStore, fileErrorRecordStore, tracker, 5
        , 10, 10, deliveryGuarantee, PIPELINE_NAME, REVISION);
    ProductionPipeline pipeline = new ProductionPipelineBuilder(MockStages.createStageLibrary(), "name",
        MockStages.createPipelineConfigurationSourceProcessorTarget()).build(runner);

    if(capturenextBatch) {
      runner.captureNextBatch(1);
    }

    return pipeline;
  }

}
