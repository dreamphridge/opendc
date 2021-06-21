/*
 * Copyright (c) 2021 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.simulator.resources

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.opendc.simulator.core.runBlockingSimulation
import org.opendc.simulator.resources.consumer.SimSpeedConsumerAdapter
import org.opendc.simulator.resources.consumer.SimWorkConsumer
import org.opendc.simulator.resources.impl.SimResourceInterpreterImpl

/**
 * Test suite for the [SimResourceAggregatorMaxMin] class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SimResourceAggregatorMaxMinTest {
    @Test
    fun testSingleCapacity() = runBlockingSimulation {
        val scheduler = SimResourceInterpreterImpl(coroutineContext, clock)

        val aggregator = SimResourceAggregatorMaxMin(scheduler)
        val forwarder = SimResourceForwarder()
        val sources = listOf(
            forwarder,
            SimResourceSource(1.0, scheduler)
        )
        sources.forEach(aggregator::addInput)

        val consumer = SimWorkConsumer(1.0, 0.5)
        val usage = mutableListOf<Double>()
        val source = SimResourceSource(1.0, scheduler)
        val adapter = SimSpeedConsumerAdapter(forwarder, usage::add)
        source.startConsumer(adapter)

        aggregator.consume(consumer)
        yield()

        assertAll(
            { assertEquals(1000, clock.millis()) },
            { assertEquals(listOf(0.0, 0.5, 0.0), usage) }
        )
    }

    @Test
    fun testDoubleCapacity() = runBlockingSimulation {
        val scheduler = SimResourceInterpreterImpl(coroutineContext, clock)

        val aggregator = SimResourceAggregatorMaxMin(scheduler)
        val sources = listOf(
            SimResourceSource(1.0, scheduler),
            SimResourceSource(1.0, scheduler)
        )
        sources.forEach(aggregator::addInput)

        val consumer = SimWorkConsumer(2.0, 1.0)
        val usage = mutableListOf<Double>()
        val adapter = SimSpeedConsumerAdapter(consumer, usage::add)

        aggregator.consume(adapter)
        yield()
        assertAll(
            { assertEquals(1000, clock.millis()) },
            { assertEquals(listOf(0.0, 2.0, 0.0), usage) }
        )
    }

    @Test
    fun testOvercommit() = runBlockingSimulation {
        val scheduler = SimResourceInterpreterImpl(coroutineContext, clock)

        val aggregator = SimResourceAggregatorMaxMin(scheduler)
        val sources = listOf(
            SimResourceSource(1.0, scheduler),
            SimResourceSource(1.0, scheduler)
        )
        sources.forEach(aggregator::addInput)

        val consumer = mockk<SimResourceConsumer>(relaxUnitFun = true)
        every { consumer.onNext(any()) }
            .returns(SimResourceCommand.Consume(4.0, 4.0, 1000))
            .andThen(SimResourceCommand.Exit)

        aggregator.consume(consumer)
        yield()
        assertEquals(1000, clock.millis())

        verify(exactly = 2) { consumer.onNext(any()) }
    }

    @Test
    fun testException() = runBlockingSimulation {
        val scheduler = SimResourceInterpreterImpl(coroutineContext, clock)

        val aggregator = SimResourceAggregatorMaxMin(scheduler)
        val sources = listOf(
            SimResourceSource(1.0, scheduler),
            SimResourceSource(1.0, scheduler)
        )
        sources.forEach(aggregator::addInput)

        val consumer = mockk<SimResourceConsumer>(relaxUnitFun = true)
        every { consumer.onNext(any()) }
            .returns(SimResourceCommand.Consume(1.0, 1.0))
            .andThenThrows(IllegalStateException("Test Exception"))

        assertThrows<IllegalStateException> { aggregator.consume(consumer) }
        yield()
        assertFalse(sources[0].isActive)
    }

    @Test
    fun testAdjustCapacity() = runBlockingSimulation {
        val scheduler = SimResourceInterpreterImpl(coroutineContext, clock)

        val aggregator = SimResourceAggregatorMaxMin(scheduler)
        val sources = listOf(
            SimResourceSource(1.0, scheduler),
            SimResourceSource(1.0, scheduler)
        )
        sources.forEach(aggregator::addInput)

        val consumer = SimWorkConsumer(4.0, 1.0)
        coroutineScope {
            launch { aggregator.consume(consumer) }
            delay(1000)
            sources[0].capacity = 0.5
        }
        yield()
        assertEquals(2334, clock.millis())
    }

    @Test
    fun testFailOverCapacity() = runBlockingSimulation {
        val scheduler = SimResourceInterpreterImpl(coroutineContext, clock)

        val aggregator = SimResourceAggregatorMaxMin(scheduler)
        val sources = listOf(
            SimResourceSource(1.0, scheduler),
            SimResourceSource(1.0, scheduler)
        )
        sources.forEach(aggregator::addInput)

        val consumer = SimWorkConsumer(1.0, 0.5)
        coroutineScope {
            launch { aggregator.consume(consumer) }
            delay(500)
            sources[0].capacity = 0.5
        }
        yield()
        assertEquals(1000, clock.millis())
    }

    @Test
    fun testCounters() = runBlockingSimulation {
        val scheduler = SimResourceInterpreterImpl(coroutineContext, clock)

        val aggregator = SimResourceAggregatorMaxMin(scheduler)
        val sources = listOf(
            SimResourceSource(1.0, scheduler),
            SimResourceSource(1.0, scheduler)
        )
        sources.forEach(aggregator::addInput)

        val consumer = mockk<SimResourceConsumer>(relaxUnitFun = true)
        every { consumer.onNext(any()) }
            .returns(SimResourceCommand.Consume(4.0, 4.0, 1000))
            .andThen(SimResourceCommand.Exit)

        aggregator.consume(consumer)
        yield()
        assertEquals(1000, clock.millis())
        assertEquals(2.0, aggregator.counters.actual) { "Actual work mismatch" }
    }
}
