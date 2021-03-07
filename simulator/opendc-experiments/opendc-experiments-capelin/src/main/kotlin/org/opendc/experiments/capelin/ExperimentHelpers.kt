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

package org.opendc.experiments.capelin.experiment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.opendc.compute.api.Flavor
import org.opendc.compute.api.Server
import org.opendc.compute.api.ServerState
import org.opendc.compute.api.ServerWatcher
import org.opendc.compute.core.metal.NODE_CLUSTER
import org.opendc.compute.core.metal.NodeEvent
import org.opendc.compute.core.metal.service.ProvisioningService
import org.opendc.compute.core.workload.VmWorkload
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.ComputeServiceEvent
import org.opendc.compute.service.driver.HostEvent
import org.opendc.compute.service.internal.ComputeServiceImpl
import org.opendc.compute.service.scheduler.AllocationPolicy
import org.opendc.compute.simulator.SimBareMetalDriver
import org.opendc.compute.simulator.SimHost
import org.opendc.compute.simulator.SimHostProvisioner
import org.opendc.experiments.capelin.monitor.ExperimentMonitor
import org.opendc.experiments.capelin.trace.Sc20StreamingParquetTraceReader
import org.opendc.format.environment.EnvironmentReader
import org.opendc.format.trace.TraceReader
import org.opendc.simulator.compute.SimFairShareHypervisorProvider
import org.opendc.simulator.compute.interference.PerformanceInterferenceModel
import org.opendc.simulator.failures.CorrelatedFaultInjector
import org.opendc.simulator.failures.FailureDomain
import org.opendc.simulator.failures.FaultInjector
import org.opendc.trace.core.EventTracer
import java.io.File
import java.time.Clock
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * The logger for this experiment.
 */
private val logger = KotlinLogging.logger {}

/**
 * Construct the failure domain for the experiments.
 */
public suspend fun createFailureDomain(
    coroutineScope: CoroutineScope,
    clock: Clock,
    seed: Int,
    failureInterval: Double,
    bareMetalProvisioner: ProvisioningService,
    chan: Channel<Unit>
): CoroutineScope {
    val job = coroutineScope.launch {
        chan.receive()
        val random = Random(seed)
        val injectors = mutableMapOf<String, FaultInjector>()
        for (node in bareMetalProvisioner.nodes()) {
            val cluster = node.metadata[NODE_CLUSTER] as String
            val injector =
                injectors.getOrPut(cluster) {
                    createFaultInjector(
                        this,
                        clock,
                        random,
                        failureInterval
                    )
                }
            injector.enqueue(node.metadata["driver"] as FailureDomain)
        }
    }
    return CoroutineScope(coroutineScope.coroutineContext + job)
}

/**
 * Obtain the [FaultInjector] to use for the experiments.
 */
public fun createFaultInjector(
    coroutineScope: CoroutineScope,
    clock: Clock,
    random: Random,
    failureInterval: Double
): FaultInjector {
    // Parameters from A. Iosup, A Framework for the Study of Grid Inter-Operation Mechanisms, 2009
    // GRID'5000
    return CorrelatedFaultInjector(
        coroutineScope,
        clock,
        iatScale = ln(failureInterval), iatShape = 1.03, // Hours
        sizeScale = ln(2.0), sizeShape = ln(1.0), // Expect 2 machines, with variation of 1
        dScale = ln(60.0), dShape = ln(60.0 * 8), // Minutes
        random = random
    )
}

/**
 * Create the trace reader from which the VM workloads are read.
 */
public fun createTraceReader(
    path: File,
    performanceInterferenceModel: PerformanceInterferenceModel,
    vms: List<String>,
    seed: Int
): Sc20StreamingParquetTraceReader {
    return Sc20StreamingParquetTraceReader(
        path,
        performanceInterferenceModel,
        vms,
        Random(seed)
    )
}

public data class ProvisionerResult(
    val metal: ProvisioningService,
    val provisioner: SimHostProvisioner,
    val compute: ComputeServiceImpl
)

/**
 * Construct the environment for a VM provisioner and return the provisioner instance.
 */
public suspend fun createProvisioner(
    coroutineScope: CoroutineScope,
    clock: Clock,
    environmentReader: EnvironmentReader,
    allocationPolicy: AllocationPolicy,
    eventTracer: EventTracer
): ProvisionerResult {
    val environment = environmentReader.use { it.construct(coroutineScope, clock) }
    val bareMetalProvisioner = environment.platforms[0].zones[0].services[ProvisioningService]

    // Wait for the bare metal nodes to be spawned
    delay(10)

    val provisioner = SimHostProvisioner(coroutineScope.coroutineContext, bareMetalProvisioner, SimFairShareHypervisorProvider())
    val hosts = provisioner.provisionAll()

    val scheduler = ComputeService(coroutineScope.coroutineContext, clock, eventTracer, allocationPolicy) as ComputeServiceImpl

    for (host in hosts) {
        scheduler.addHost(host)
    }

    // Wait for the hypervisors to be spawned
    delay(10)

    return ProvisionerResult(bareMetalProvisioner, provisioner, scheduler)
}

/**
 * Attach the specified monitor to the VM provisioner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun attachMonitor(
    coroutineScope: CoroutineScope,
    clock: Clock,
    scheduler: ComputeService,
    monitor: ExperimentMonitor
) {

    val hypervisors = scheduler.hosts

    // Monitor hypervisor events
    for (hypervisor in hypervisors) {
        // TODO Do not expose Host directly but use Hypervisor class.
        val server = (hypervisor as SimHost).node
        monitor.reportHostStateChange(clock.millis(), hypervisor, server)
        server.events
            .onEach { event ->
                val time = clock.millis()
                when (event) {
                    is NodeEvent.StateChanged -> {
                        monitor.reportHostStateChange(time, hypervisor, event.node)
                    }
                }
            }
            .launchIn(coroutineScope)
        hypervisor.events
            .onEach { event ->
                when (event) {
                    is HostEvent.SliceFinished -> monitor.reportHostSlice(
                        clock.millis(),
                        event.requestedBurst,
                        event.grantedBurst,
                        event.overcommissionedBurst,
                        event.interferedBurst,
                        event.cpuUsage,
                        event.cpuDemand,
                        event.numberOfDeployedImages,
                        (event.driver as SimHost).node
                    )
                }
            }
            .launchIn(coroutineScope)

        val driver = server.metadata["driver"] as SimBareMetalDriver
        driver.powerDraw
            .onEach { monitor.reportPowerConsumption(server, it) }
            .launchIn(coroutineScope)
    }

    scheduler.events
        .onEach { event ->
            when (event) {
                is ComputeServiceEvent.MetricsAvailable ->
                    monitor.reportProvisionerMetrics(clock.millis(), event)
            }
        }
        .launchIn(coroutineScope)
}

/**
 * Process the trace.
 */
public suspend fun processTrace(
    coroutineScope: CoroutineScope,
    clock: Clock,
    reader: TraceReader<VmWorkload>,
    scheduler: ComputeService,
    chan: Channel<Unit>,
    monitor: ExperimentMonitor
) {
    val client = scheduler.newClient()
    try {
        var submitted = 0

        while (reader.hasNext()) {
            val (time, workload) = reader.next()

            submitted++
            delay(max(0, time - clock.millis()))
            coroutineScope.launch {
                chan.send(Unit)
                val server = client.newServer(
                    workload.image.name,
                    workload.image,
                    Flavor(
                        workload.image.tags["cores"] as Int,
                        workload.image.tags["required-memory"] as Long
                    )
                )

                server.watch(object : ServerWatcher {
                    override fun onStateChanged(server: Server, newState: ServerState) {
                        monitor.reportVmStateChange(clock.millis(), server, newState)
                    }
                })
            }
        }

        scheduler.events
            .takeWhile {
                when (it) {
                    is ComputeServiceEvent.MetricsAvailable ->
                        it.inactiveVmCount + it.failedVmCount != submitted
                }
            }
            .collect()
        delay(1)
    } finally {
        reader.close()
        client.close()
    }
}
