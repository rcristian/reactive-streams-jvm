package org.reactivestreams.tck;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.TestEnvironment.*;
import org.reactivestreams.tck.support.Optional;
import org.reactivestreams.tck.support.SubscriberWhiteboxVerificationRules;
import org.reactivestreams.tck.support.TestException;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.testng.Assert.assertTrue;

/**
 * Provides tests for verifying {@link org.reactivestreams.Subscriber} and {@link org.reactivestreams.Subscription} specification rules.
 *
 * @see org.reactivestreams.Subscriber
 * @see org.reactivestreams.Subscription
 */
public abstract class SubscriberWhiteboxVerification<T> extends WithHelperPublisher<T>
  implements SubscriberWhiteboxVerificationRules {

  private final TestEnvironment env;

  protected SubscriberWhiteboxVerification(TestEnvironment env) {
    this.env = env;
  }

  // USER API

  /**
   * This is the main method you must implement in your test incarnation.
   * It must create a new {@link org.reactivestreams.Subscriber} instance to be subjected to the testing logic.
   *
   * In order to be meaningfully testable your Subscriber must inform the given
   * `WhiteboxSubscriberProbe` of the respective events having been received.
   */
  public abstract Subscriber<T> createSubscriber(WhiteboxSubscriberProbe<T> probe);

  // ENV SETUP

  /**
   * Executor service used by the default provided asynchronous Publisher.
   * @see #createHelperPublisher(long)
   */
  private ExecutorService publisherExecutor;
  @BeforeClass public void startPublisherExecutorService() { publisherExecutor = Executors.newFixedThreadPool(4); }
  @AfterClass public void shutdownPublisherExecutorService() { if (publisherExecutor != null) publisherExecutor.shutdown(); }
  @Override public ExecutorService publisherExecutorService() { return publisherExecutor; }

  ////////////////////// TEST ENV CLEANUP /////////////////////////////////////

  @BeforeMethod
  public void setUp() throws Exception {
    env.clearAsyncErrors();
  }

  ////////////////////// TEST SETUP VERIFICATION //////////////////////////////

  @Test
  public void required_exerciseWhiteboxHappyPath() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws InterruptedException {
        stage.puppet().triggerRequest(1);
        stage.puppet().triggerRequest(1);

        long receivedRequests = stage.expectRequest();

        stage.signalNext();
        stage.probe.expectNext(stage.lastT);

        stage.puppet().triggerRequest(1);
        if (receivedRequests == 1) {
          stage.expectRequest();
        }

        stage.signalNext();
        stage.probe.expectNext(stage.lastT);

        stage.puppet().signalCancel();
        stage.expectCancelling();

        stage.verifyNoAsyncErrors();
      }
    });
  }

  ////////////////////// SPEC RULE VERIFICATION ///////////////////////////////

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.1
  @Override @Test
  public void required_spec201_mustSignalDemandViaSubscriptionRequest() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws InterruptedException {
        stage.puppet().triggerRequest(1);
        stage.expectRequest();

        stage.signalNext();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.2
  @Override @Test
  public void untested_spec202_shouldAsynchronouslyDispatch() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.3
  @Override @Test
  public void required_spec203_mustNotCallMethodsOnSubscriptionOrPublisherInOnComplete() throws Throwable {
    subscriberTestWithoutSetup(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws Throwable {
        final Subscription subs = new Subscription() {
          @Override
          public void request(long n) {
            final Optional<StackTraceElement> onCompleteStackTraceElement = env.findCallerMethodInStackTrace("onComplete");
            if (onCompleteStackTraceElement.isDefined()) {
              final StackTraceElement stackElem = onCompleteStackTraceElement.get();
              env.flop(String.format("Subscription::request MUST NOT be called from Subscriber::onComplete (Rule 2.3)! (Caller: %s::%s line %d)",
                                     stackElem.getClassName(), stackElem.getMethodName(), stackElem.getLineNumber()));
            }
          }

          @Override
          public void cancel() {
            final Optional<StackTraceElement> onCompleteStackElement = env.findCallerMethodInStackTrace("onComplete");
            if (onCompleteStackElement.isDefined()) {
              final StackTraceElement stackElem = onCompleteStackElement.get();
              env.flop(String.format("Subscription::cancel MUST NOT be called from Subscriber::onComplete (Rule 2.3)! (Caller: %s::%s line %d)",
                                     stackElem.getClassName(), stackElem.getMethodName(), stackElem.getLineNumber()));
            }
          }
        };

        stage.probe = stage.createWhiteboxSubscriberProbe(env);
        final Subscriber<T> sub = createSubscriber(stage.probe);

        sub.onSubscribe(subs);
        sub.onComplete();

        env.verifyNoAsyncErrorsNoDelay();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.3
  @Override @Test
  public void required_spec203_mustNotCallMethodsOnSubscriptionOrPublisherInOnError() throws Throwable {
    subscriberTestWithoutSetup(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws Throwable {
        final Subscription subs = new Subscription() {
          @Override
          public void request(long n) {
            Throwable thr = new Throwable();
            for (StackTraceElement stackElem : thr.getStackTrace()) {
              if (stackElem.getMethodName().equals("onError")) {
                env.flop(String.format("Subscription::request MUST NOT be called from Subscriber::onError (Rule 2.3)! (Caller: %s::%s line %d)",
                                       stackElem.getClassName(), stackElem.getMethodName(), stackElem.getLineNumber()));
              }
            }
          }

          @Override
          public void cancel() {
            Throwable thr = new Throwable();
            for (StackTraceElement stackElem : thr.getStackTrace()) {
              if (stackElem.getMethodName().equals("onError")) {
                env.flop(String.format("Subscription::cancel MUST NOT be called from Subscriber::onError (Rule 2.3)! (Caller: %s::%s line %d)",
                                       stackElem.getClassName(), stackElem.getMethodName(), stackElem.getLineNumber()));
              }
            }
          }
        };

        stage.probe = stage.createWhiteboxSubscriberProbe(env);
        final Subscriber<T> sub = createSubscriber(stage.probe);

        sub.onSubscribe(subs);
        sub.onError(new TestException());

        env.verifyNoAsyncErrorsNoDelay();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.4
  @Override @Test
  public void untested_spec204_mustConsiderTheSubscriptionAsCancelledInAfterRecievingOnCompleteOrOnError() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.5
  @Override @Test
  public void required_spec205_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws Throwable {
        // try to subscribe another time, if the subscriber calls `probe.registerOnSubscribe` the test will fail
        final Latch secondSubscriptionCancelled = new Latch(env);
        final Subscriber<? super T> sub = stage.sub();
        final Subscription subscription = new Subscription() {
          @Override
          public void request(long elements) {
            // ignore...
          }

          @Override
          public void cancel() {
            secondSubscriptionCancelled.close();
          }

          @Override
          public String toString() {
            return "SecondSubscription(should get cancelled)";
          }
        };
        sub.onSubscribe(subscription);

        secondSubscriptionCancelled.expectClose("Expected 2nd Subscription given to subscriber to be cancelled, but `Subscription.cancel()` was not called");
        env.verifyNoAsyncErrors();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.6
  @Override @Test
  public void untested_spec206_mustCallSubscriptionCancelIfItIsNoLongerValid() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.7
  @Override @Test
  public void untested_spec207_mustEnsureAllCallsOnItsSubscriptionTakePlaceFromTheSameThreadOrTakeCareOfSynchronization() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
    // the same thread part of the clause can be verified but that is not very useful, or is it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.8
  @Override @Test
  public void required_spec208_mustBePreparedToReceiveOnNextSignalsAfterHavingCalledSubscriptionCancel() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws InterruptedException {
        stage.puppet().triggerRequest(1);
        stage.puppet().signalCancel();
        stage.signalNext();

        stage.puppet().triggerRequest(1);
        stage.puppet().triggerRequest(1);

        stage.verifyNoAsyncErrors();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.9
  @Override @Test
  public void required_spec209_mustBePreparedToReceiveAnOnCompleteSignalWithPrecedingRequestCall() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws InterruptedException {
        stage.puppet().triggerRequest(1);
        stage.sendCompletion();
        stage.probe.expectCompletion();

        stage.verifyNoAsyncErrors();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.9
  @Override @Test
  public void required_spec209_mustBePreparedToReceiveAnOnCompleteSignalWithoutPrecedingRequestCall() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws InterruptedException {
        stage.sendCompletion();
        stage.probe.expectCompletion();

        stage.verifyNoAsyncErrors();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.10
  @Override @Test
  public void required_spec210_mustBePreparedToReceiveAnOnErrorSignalWithPrecedingRequestCall() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws InterruptedException {
        stage.puppet().triggerRequest(1);
        stage.puppet().triggerRequest(1);

        Exception ex = new TestException();
        stage.sendError(ex);
        stage.probe.expectError(ex);

        env.verifyNoAsyncErrorsNoDelay();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.10
  @Override @Test
  public void required_spec210_mustBePreparedToReceiveAnOnErrorSignalWithoutPrecedingRequestCall() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws InterruptedException {
        Exception ex = new TestException();
        stage.sendError(ex);
        stage.probe.expectError(ex);

        env.verifyNoAsyncErrorsNoDelay();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.11
  @Override @Test
  public void untested_spec211_mustMakeSureThatAllCallsOnItsMethodsHappenBeforeTheProcessingOfTheRespectiveEvents() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.12
  @Override @Test
  public void untested_spec212_mustNotCallOnSubscribeMoreThanOnceBasedOnObjectEquality_specViolation() throws Throwable {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.13
  @Override @Test
  public void untested_spec213_failingOnSignalInvocation() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.13
  @Override @Test
  public void required_spec213_onSubscribe_mustThrowNullPointerExceptionWhenParametersAreNull() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws Throwable {

        final Subscriber<? super T> sub = stage.sub();
        boolean gotNPE = false;
        try {
          sub.onSubscribe(null);
        } catch (final NullPointerException expected) {
          gotNPE = true;
        } 

        assertTrue(gotNPE, "onSubscribe(null) did not throw NullPointerException");
        env.verifyNoAsyncErrorsNoDelay();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.13
  @Override @Test
  public void required_spec213_onNext_mustThrowNullPointerExceptionWhenParametersAreNull() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws Throwable {

        final Subscriber<? super T> sub = stage.sub();
        boolean gotNPE = false;
        try {
          sub.onNext(null);
        } catch (final NullPointerException expected) {
          gotNPE = true;
        }

        assertTrue(gotNPE, "onNext(null) did not throw NullPointerException");
        env.verifyNoAsyncErrorsNoDelay();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#2.13
  @Override @Test
  public void required_spec213_onError_mustThrowNullPointerExceptionWhenParametersAreNull() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws Throwable {

          final Subscriber<? super T> sub = stage.sub();
          boolean gotNPE = false;
          try {
            sub.onError(null);
          } catch (final NullPointerException expected) {
            gotNPE = true;
          } finally {
            assertTrue(gotNPE, "onError(null) did not throw NullPointerException");
          }

        env.verifyNoAsyncErrorsNoDelay();
      }
    });
  }


  ////////////////////// SUBSCRIPTION SPEC RULE VERIFICATION //////////////////

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#3.1
  @Override @Test
  public void untested_spec301_mustNotBeCalledOutsideSubscriberContext() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#3.8
  @Override @Test
  public void required_spec308_requestMustRegisterGivenNumberElementsToBeProduced() throws Throwable {
    subscriberTest(new TestStageTestRun() {
      @Override
      public void run(WhiteboxTestStage stage) throws InterruptedException {
        stage.puppet().triggerRequest(2);
        stage.probe.expectNext(stage.signalNext());
        stage.probe.expectNext(stage.signalNext());

        stage.probe.expectNone();
        stage.puppet().triggerRequest(3);

        stage.verifyNoAsyncErrors();
      }
    });
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#3.10
  @Override @Test
  public void untested_spec310_requestMaySynchronouslyCallOnNextOnSubscriber() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#3.11
  @Override @Test
  public void untested_spec311_requestMaySynchronouslyCallOnCompleteOrOnError() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#3.14
  @Override @Test
  public void untested_spec314_cancelMayCauseThePublisherToShutdownIfNoOtherSubscriptionExists() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#3.15
  @Override @Test
  public void untested_spec315_cancelMustNotThrowExceptionAndMustSignalOnError() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  // Verifies rule: https://github.com/reactive-streams/reactive-streams-jvm#3.16
  @Override @Test
  public void untested_spec316_requestMustNotThrowExceptionAndMustOnErrorTheSubscriber() throws Exception {
    notVerified(); // cannot be meaningfully tested, or can it?
  }

  /////////////////////// ADDITIONAL "COROLLARY" TESTS ////////////////////////

  /////////////////////// TEST INFRASTRUCTURE /////////////////////////////////

  abstract class TestStageTestRun {
    public abstract void run(WhiteboxTestStage stage) throws Throwable;
  }

  /**
   * Prepares subscriber and publisher pair (by subscribing the first to the latter),
   * and then hands over the tests {@link WhiteboxTestStage} over to the test.
   *
   * The test stage is, like in a puppet show, used to orchestrate what each participant should do.
   * Since this is a whitebox test, this allows the stage to completely control when and how to signal / expect signals.
   */
  public void subscriberTest(TestStageTestRun body) throws Throwable {
    WhiteboxTestStage stage = new WhiteboxTestStage(env, true);
    body.run(stage);
  }

  /**
   * Provides a {@link WhiteboxTestStage} without performing any additional setup,
   * like the {@link #subscriberTest(SubscriberWhiteboxVerification.TestStageTestRun)} would.
   *
   * Use this method to write tests in which you need full control over when and how the initial {@code subscribe} is signalled.
   */
  public void subscriberTestWithoutSetup(TestStageTestRun body) throws Throwable {
    WhiteboxTestStage stage = new WhiteboxTestStage(env, false);
    body.run(stage);
  }

  /**
   * Test for feature that MAY be implemented. This test will be marked as SKIPPED if it fails.
   */
  public void optionalSubscriberTestWithoutSetup(TestStageTestRun body) throws Throwable {
    try {
      subscriberTestWithoutSetup(body);
    } catch (Exception ex) {
      notVerified("Skipped because tested publisher does NOT implement this OPTIONAL requirement.");
    }
  }

  public class WhiteboxTestStage extends ManualPublisher<T> {
    public Publisher<T> pub;
    public ManualSubscriber<T> tees; // gives us access to a stream T values
    public WhiteboxSubscriberProbe<T> probe;

    public T lastT = null;

    public WhiteboxTestStage(TestEnvironment env) throws InterruptedException {
      this(env, true);
    }

    public WhiteboxTestStage(TestEnvironment env, boolean runDefaultInit) throws InterruptedException {
      super(env);
      if (runDefaultInit) {
        pub = this.createHelperPublisher(Long.MAX_VALUE);
        tees = env.newManualSubscriber(pub);
        probe = new WhiteboxSubscriberProbe<T>(env, subscriber);
        subscribe(createSubscriber(probe));
        probe.puppet.expectCompletion(env.defaultTimeoutMillis(), String.format("Subscriber %s did not `registerOnSubscribe`", sub()));
      }
    }

    public Subscriber<? super T> sub() {
      return subscriber.value();
    }

    public SubscriberPuppet puppet() {
      return probe.puppet();
    }

    public WhiteboxSubscriberProbe<T> probe() {
      return probe;
    }

    public Publisher<T> createHelperPublisher(long elements) {
      return SubscriberWhiteboxVerification.this.createHelperPublisher(elements);
    }

    public WhiteboxSubscriberProbe<T> createWhiteboxSubscriberProbe(TestEnvironment env) {
      return new WhiteboxSubscriberProbe<T>(env, subscriber);
    }

    public T signalNext() throws InterruptedException {
      return signalNext(nextT());
    }

    private T signalNext(T element) throws InterruptedException {
      sendNext(element);
      return element;
    }

    public T nextT() throws InterruptedException {
      lastT = tees.requestNextElement();
      return lastT;
    }

    public void verifyNoAsyncErrors() {
      env.verifyNoAsyncErrors();
    }
  }

  /**
   * This class is intented to be used as {@code Subscriber} decorator and should be used in {@code pub.subscriber(...)} calls,
   * in order to allow intercepting calls on the underlying {@code Subscriber}.
   * This delegation allows the proxy to implement {@link BlackboxProbe} assertions.
   */
  public static class BlackboxSubscriberProxy<T> extends BlackboxProbe<T> implements Subscriber<T> {

    public BlackboxSubscriberProxy(TestEnvironment env, Subscriber<T> subscriber) {
      super(env, Promise.<Subscriber<? super T>>completed(env, subscriber));
    }

    @Override
    public void onSubscribe(Subscription s) {
      sub().onSubscribe(s);
    }

    @Override
    public void onNext(T t) {
      registerOnNext(t);
      sub().onNext(t);
    }

    @Override
    public void onError(Throwable cause) {
      registerOnError(cause);
      sub().onError(cause);
    }

    @Override
    public void onComplete() {
      registerOnComplete();
      sub().onComplete();
    }
  }

  public static class BlackboxProbe<T> implements SubscriberProbe<T> {
    protected final TestEnvironment env;
    protected final Promise<Subscriber<? super T>> subscriber;

    protected final Receptacle<T> elements;
    protected final Promise<Throwable> error;

    public BlackboxProbe(TestEnvironment env, Promise<Subscriber<? super T>> subscriber) {
      this.env = env;
      this.subscriber = subscriber;
      elements = new Receptacle<T>(env);
      error = new Promise<Throwable>(env);
    }

    @Override
    public void registerOnNext(T element) {
      elements.add(element);
    }

    @Override
    public void registerOnComplete() {
      try {
        elements.complete();
      } catch (IllegalStateException ex) {
        // "Queue full", onComplete was already called
        env.flop("subscriber::onComplete was called a second time, which is illegal according to Rule 1.7");
      }
    }

    @Override
    public void registerOnError(Throwable cause) {
      try {
        error.complete(cause);
      } catch (IllegalStateException ex) {
        // "Queue full", onError was already called
        env.flop("subscriber::onError was called a second time, which is illegal according to Rule 1.7");
      }
    }

    public T expectNext() throws InterruptedException {
      return elements.next(env.defaultTimeoutMillis(), String.format("Subscriber %s did not call `registerOnNext(_)`", sub()));
    }

    public void expectNext(T expected) throws InterruptedException {
      expectNext(expected, env.defaultTimeoutMillis());
    }

    public void expectNext(T expected, long timeoutMillis) throws InterruptedException {
      T received = elements.next(timeoutMillis, String.format("Subscriber %s did not call `registerOnNext(%s)`", sub(), expected));
      if (!received.equals(expected)) {
        env.flop(String.format("Subscriber %s called `registerOnNext(%s)` rather than `registerOnNext(%s)`", sub(), received, expected));
      }
    }

    public Subscriber<? super T> sub() {
      return subscriber.value();
    }

    public void expectCompletion() throws InterruptedException {
      expectCompletion(env.defaultTimeoutMillis());
    }

    public void expectCompletion(long timeoutMillis) throws InterruptedException {
      expectCompletion(timeoutMillis, String.format("Subscriber %s did not call `registerOnComplete()`", sub()));
    }

    public void expectCompletion(long timeoutMillis, String msg) throws InterruptedException {
      elements.expectCompletion(timeoutMillis, msg);
    }

    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    public <E extends Throwable> void expectErrorWithMessage(Class<E> expected, String requiredMessagePart) throws InterruptedException {
      final E err = expectError(expected);
      String message = err.getMessage();
      assertTrue(message.contains(requiredMessagePart),
        String.format("Got expected exception %s but missing message [%s], was: %s", err.getClass(), requiredMessagePart, expected));
    }

    public <E extends Throwable> E expectError(Class<E> expected) throws InterruptedException {
      return expectError(expected, env.defaultTimeoutMillis());
    }

    @SuppressWarnings({"unchecked", "ThrowableResultOfMethodCallIgnored"})
    public <E extends Throwable> E expectError(Class<E> expected, long timeoutMillis) throws InterruptedException {
      error.expectCompletion(timeoutMillis, String.format("Subscriber %s did not call `registerOnError(%s)`", sub(), expected));
      if (error.value() == null) {
        return env.flopAndFail(String.format("Subscriber %s did not call `registerOnError(%s)`", sub(), expected));
      } else if (expected.isInstance(error.value())) {
        return (E) error.value();
      } else {
        return env.flopAndFail(String.format("Subscriber %s called `registerOnError(%s)` rather than `registerOnError(%s)`", sub(), error.value(), expected));
      }
    }

    public void expectError(Throwable expected) throws InterruptedException {
      expectError(expected, env.defaultTimeoutMillis());
    }

    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    public void expectError(Throwable expected, long timeoutMillis) throws InterruptedException {
      error.expectCompletion(timeoutMillis, String.format("Subscriber %s did not call `registerOnError(%s)`", sub(), expected));
      if (error.value() != expected) {
        env.flop(String.format("Subscriber %s called `registerOnError(%s)` rather than `registerOnError(%s)`", sub(), error.value(), expected));
      }
    }

    public void expectNone() throws InterruptedException {
      expectNone(env.defaultTimeoutMillis());
    }

    public void expectNone(long withinMillis) throws InterruptedException {
      elements.expectNone(withinMillis, "Expected nothing");
    }

  }

  public static class WhiteboxSubscriberProbe<T> extends BlackboxProbe<T> implements SubscriberPuppeteer {
    protected Promise<SubscriberPuppet> puppet;

    public WhiteboxSubscriberProbe(TestEnvironment env, Promise<Subscriber<? super T>> subscriber) {
      super(env, subscriber);
      puppet = new Promise<SubscriberPuppet>(env);
    }

    private SubscriberPuppet puppet() {
      return puppet.value();
    }

    @Override
    public void registerOnSubscribe(SubscriberPuppet p) {
      if (!puppet.isCompleted()) {
        puppet.complete(p);
      } 
    }

  }

  public interface SubscriberPuppeteer {

    /**
     * Must be called by the test subscriber when it has successfully registered a subscription
     * inside the `onSubscribe` method.
     */
    void registerOnSubscribe(SubscriberPuppet puppet);
  }

  public interface SubscriberProbe<T> {

    /**
     * Must be called by the test subscriber when it has received an`onNext` event.
     */
    void registerOnNext(T element);

    /**
     * Must be called by the test subscriber when it has received an `onComplete` event.
     */
    void registerOnComplete();

    /**
     * Must be called by the test subscriber when it has received an `onError` event.
     */
    void registerOnError(Throwable cause);

  }

  public interface SubscriberPuppet {
    void triggerRequest(long elements);

    void signalCancel();
  }

  public void notVerified() {
    throw new SkipException("Not verified using this TCK.");
  }

  public void notVerified(String msg) {
    throw new SkipException(msg);
  }
}
