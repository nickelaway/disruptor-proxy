/*
 * Copyright 2015 LMAX Ltd.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.lmax.tool.disruptor;

import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.FatalExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public abstract class AbstractRingBufferProxyGeneratorTest
{
    private static final int ITERATIONS = 3;
    private final GeneratorType generatorType;

    protected AbstractRingBufferProxyGeneratorTest(final GeneratorType generatorType)
    {
        this.generatorType = generatorType;
    }

    public static final class ConcreteClass
    {

    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldBlowUpIfSuppliedClassIsNotAnInterface() throws Exception
    {
        final Disruptor<ProxyMethodInvocation> disruptor =
                createDisruptor(Executors.newSingleThreadExecutor(), 1024);
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator ringBufferProxyGenerator = generatorFactory.newProxy(generatorType, new ConfigurableValidator(false, true));
        ringBufferProxyGenerator.createRingBufferProxy(ConcreteClass.class, disruptor, OverflowStrategy.DROP, new ConcreteClass());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionIfDisruptorInstanceDoesNotHaveAnExceptionHandler() throws Exception
    {
        final Disruptor<ProxyMethodInvocation> disruptor =
                new Disruptor<ProxyMethodInvocation>(new RingBufferProxyEventFactory(), 1024, Executors.newSingleThreadExecutor());
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator ringBufferProxyGenerator = generatorFactory.newProxy(generatorType);
        final ListenerImpl implementation = new ListenerImpl();
        ringBufferProxyGenerator.createRingBufferProxy(Listener.class, disruptor, OverflowStrategy.DROP, implementation);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldNotValidateRingBufferProxyAnnotationByDefaultToPreserveBackwardsCompatibility() throws Exception
    {
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator generator = generatorFactory.create(generatorType);
        generator.createRingBufferProxy(MyDisruptorProxyWithoutTheDisruptorAnnotation.class,
                createDisruptor(Executors.newSingleThreadExecutor(), 1024), OverflowStrategy.DROP, new StubImplementationForInterface());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldValidateRingBufferProxyAnnotationIfConfiguredThatWay() throws Exception
    {
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator generator = generatorFactory.newProxy(generatorType, new ConfigurableValidator(true, false));
        generator.createRingBufferProxy(MyDisruptorProxyWithoutTheDisruptorAnnotation.class,
                createDisruptor(Executors.newSingleThreadExecutor(), 1024), OverflowStrategy.DROP, new StubImplementationForInterface());
    }

    @SuppressWarnings("deprecation")
    @Test(expected = IllegalStateException.class)
    public void shouldValidateExceptionHandlerByDefaultToPreserveBackwardsCompatibility() throws Exception
    {
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator generator = generatorFactory.create(generatorType);
        final Disruptor<ProxyMethodInvocation> disruptor = new Disruptor<ProxyMethodInvocation>(new RingBufferProxyEventFactory(), 1024, Executors.newSingleThreadExecutor());
        generator.createRingBufferProxy(MyDisruptorProxyWithoutTheDisruptorAnnotation.class, disruptor,
                OverflowStrategy.DROP, new StubImplementationForInterface());
    }

    @Test
    public void shouldNotValidateExceptionHandlerIfConfiguredThatWay() throws Exception
    {
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator generator = generatorFactory.newProxy(generatorType, new ConfigurableValidator(false, false));
        final Disruptor<ProxyMethodInvocation> disruptor = new Disruptor<ProxyMethodInvocation>(new RingBufferProxyEventFactory(), 1024, Executors.newSingleThreadExecutor());
        generator.createRingBufferProxy(MyDisruptorProxyWithoutTheDisruptorAnnotation.class, disruptor,
                OverflowStrategy.DROP, new StubImplementationForInterface());
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Test
    public void shouldProxy()
    {
        final Disruptor<ProxyMethodInvocation> disruptor = createDisruptor(Executors.newSingleThreadExecutor(), 1024);
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator ringBufferProxyGenerator = generatorFactory.newProxy(generatorType);

        final ListenerImpl implementation = new ListenerImpl();
        final Listener listener = ringBufferProxyGenerator.createRingBufferProxy(Listener.class, disruptor, OverflowStrategy.DROP, implementation);
        disruptor.start();

        for(int i = 0; i < 3; i++)
        {
            listener.onString("single string " + i);
            listener.onFloatAndInt((float) i, i);
            listener.onVoid();
            listener.onObjectArray(new Double[]{(double) i});
            listener.onMixedMultipleArgs(0, 1, "a", "b", 2);
        }

        RingBuffer<ProxyMethodInvocation> ringBuffer = disruptor.getRingBuffer();
        while (ringBuffer.getMinimumGatingSequence() != ringBuffer.getCursor())
        {
            // Spin
        }

        disruptor.shutdown();
        Executors.newSingleThreadExecutor().shutdown();

        assertThat(implementation.getLastStringValue(), is("single string 2"));
        assertThat(implementation.getLastFloatValue(), is((float) 2));
        assertThat(implementation.getLastIntValue(), is(2));
        assertThat(implementation.getVoidInvocationCount(), is(3));
        assertThat(implementation.getMixedArgsInvocationCount(), is(3));
        assertThat(implementation.getLastDoubleArray(), is(equalTo(new Double[] {(double) 2})));
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Test
    public void shouldProxyMultipleImplementations()
    {
        final Disruptor<ProxyMethodInvocation> disruptor = createDisruptor(Executors.newCachedThreadPool(), 1024);
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator ringBufferProxyGenerator = generatorFactory.newProxy(generatorType);

        final ListenerImpl[] implementations = new ListenerImpl[]
        {
            new ListenerImpl(), new ListenerImpl()
        };

        final Listener listener = ringBufferProxyGenerator.createRingBufferProxy(Listener.class, disruptor, OverflowStrategy.DROP, implementations);
        disruptor.start();

        for(int i = 0; i < ITERATIONS; i++)
        {
            listener.onString("single string " + i);
            listener.onFloatAndInt((float) i, i);
            listener.onVoid();
            listener.onObjectArray(new Double[]{(double) i});
            listener.onMixedMultipleArgs(0, 1, "a", "b", 2);
        }

        RingBuffer<ProxyMethodInvocation> ringBuffer = disruptor.getRingBuffer();
        while (ringBuffer.getMinimumGatingSequence() != ringBuffer.getCursor())
        {
            // Spin
        }

        disruptor.shutdown();
        Executors.newCachedThreadPool().shutdown();

        for (ListenerImpl implementation : implementations)
        {
            assertThat(implementation.getLastStringValue(), is("single string 2"));
            assertThat(implementation.getLastFloatValue(), is((float) 2));
            assertThat(implementation.getLastIntValue(), is(2));
            assertThat(implementation.getVoidInvocationCount(), is(3));
            assertThat(implementation.getMixedArgsInvocationCount(), is(3));
            assertThat(implementation.getLastDoubleArray(), is(equalTo(new Double[] {(double) 2})));
        }
    }

    @Test
    public void shouldDropMessagesIfRingBufferIsFull() throws Exception
    {
        final Disruptor<ProxyMethodInvocation> disruptor = createDisruptor(Executors.newSingleThreadExecutor(), 4);
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator ringBufferProxyGenerator = generatorFactory.newProxy(generatorType);

        final CountDownLatch latch = new CountDownLatch(1);
        final BlockingOverflowTest implementation = new BlockingOverflowTest(latch);
        final OverflowTest listener = ringBufferProxyGenerator.createRingBufferProxy(OverflowTest.class, disruptor, OverflowStrategy.DROP, implementation);
        disruptor.start();

        for(int i = 0; i < 8; i++)
        {
            listener.invoke();
        }

        latch.countDown();

        Thread.sleep(250L);

        disruptor.shutdown();
        Executors.newSingleThreadExecutor().shutdown();

        assertThat(implementation.getInvocationCount(), is(4));
    }

    @Test
    public void shouldNotifyBatchListenerImplementationOfEndOfBatch() throws Exception
    {
        final Disruptor<ProxyMethodInvocation> disruptor = createDisruptor(Executors.newSingleThreadExecutor(), 4);
        final RingBufferProxyGeneratorFactory generatorFactory = new RingBufferProxyGeneratorFactory();
        final RingBufferProxyGenerator ringBufferProxyGenerator = generatorFactory.newProxy(generatorType);

        final BatchAwareListenerImpl implementation = new BatchAwareListenerImpl();
        final Listener listener = ringBufferProxyGenerator.createRingBufferProxy(Listener.class, disruptor, OverflowStrategy.DROP, implementation);
        disruptor.start();

        listener.onString("foo1");
        listener.onString("foo2");
        listener.onString("foo3");
        listener.onString("foo4");


        long timeoutAt = System.currentTimeMillis() + 2000L;

        while(implementation.getBatchCount() == 0 && System.currentTimeMillis() < timeoutAt)
        {
            Thread.sleep(1);
        }

        final int firstBatchCount = implementation.getBatchCount();
        assertThat(firstBatchCount, is(not(0)));

        listener.onVoid();
        listener.onVoid();
        listener.onVoid();

        timeoutAt = System.currentTimeMillis() + 2000L;

        while(implementation.getBatchCount() == firstBatchCount && System.currentTimeMillis() < timeoutAt)
        {
            Thread.sleep(1);
        }

        disruptor.shutdown();
        Executors.newSingleThreadExecutor().shutdown();

        assertThat(implementation.getBatchCount() > firstBatchCount, is(true));
    }

    @Test
    public void shouldAllowProxyToInheritMethodsFromOtherInterfaces() throws Exception
    {
        final Disruptor<ProxyMethodInvocation> disruptor = createDisruptor(new ScheduledThreadPoolExecutor(1), 1024);
        disruptor.handleExceptionsWith(new ThrowExceptionHandler());

        final CountDownLatch bothMethodsAreCalled = new CountDownLatch(2);
        final ProxyWhichExtendsAnotherInterface catDog = new RingBufferProxyGeneratorFactory()
                .newProxy(generatorType)
                .createRingBufferProxy(ProxyWhichExtendsAnotherInterface.class,
                        disruptor,
                        OverflowStrategy.DROP, new ProxyWhichExtendsAnotherInterface()
                        {
                            @Override
                            public void meow(String meow, int age)
                            {
                                bothMethodsAreCalled.countDown();
                            }

                            @Override
                            public void interitedMethodBark(boolean bark)
                            {
                                bothMethodsAreCalled.countDown();
                            }
                        });
        disruptor.start();

        catDog.meow("meow", 3);
        catDog.interitedMethodBark(true);

        assertTrue(bothMethodsAreCalled.await(2, TimeUnit.SECONDS));
    }

    public interface AnotherInterface
    {
        void interitedMethodBark(boolean bark);
    }

    @DisruptorProxy
    public interface ProxyWhichExtendsAnotherInterface extends AnotherInterface
    {
        void meow(String meow, int age);
    }

    private static final class BlockingOverflowTest implements OverflowTest
    {
        private final CountDownLatch blocker;
        private final AtomicInteger invocationCount = new AtomicInteger(0);

        private BlockingOverflowTest(final CountDownLatch blocker)
        {
            this.blocker = blocker;
        }

        @Override
        public void invoke()
        {
            try
            {
                blocker.await();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException();
            }
            invocationCount.incrementAndGet();
        }

        int getInvocationCount()
        {
            return invocationCount.get();
        }
    }

    @DisruptorProxy
    public interface OverflowTest
    {
        void invoke();
    }

    public interface MyDisruptorProxyWithoutTheDisruptorAnnotation
    {
    }

    private Disruptor<ProxyMethodInvocation> createDisruptor(final ExecutorService executor, final int ringBufferSize)
    {
        final Disruptor<ProxyMethodInvocation> disruptor = new Disruptor<ProxyMethodInvocation>(new RingBufferProxyEventFactory(), ringBufferSize, executor);
        disruptor.handleExceptionsWith(new FatalExceptionHandler());
        return disruptor;
    }

    private static class StubImplementationForInterface implements MyDisruptorProxyWithoutTheDisruptorAnnotation
    {
    }

    private static class ThrowExceptionHandler implements ExceptionHandler
    {
        @Override
        public void handleEventException(Throwable ex, long sequence, Object event)
        {
            throw new RuntimeException("fail " + ex.getMessage());
        }

        @Override
        public void handleOnStartException(Throwable ex)
        {
            throw new RuntimeException("fail " + ex.getMessage());
        }

        @Override
        public void handleOnShutdownException(Throwable ex)
        {
            throw new RuntimeException("fail " + ex.getMessage());
        }
    }
}
