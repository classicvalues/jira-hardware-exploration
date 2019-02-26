package com.atlassian.performance.tools.lib.infrastructure

import com.atlassian.performance.tools.concurrency.api.submitWithLogContext
import com.atlassian.performance.tools.infrastructure.api.virtualusers.MulticastVirtualUsers
import com.atlassian.performance.tools.infrastructure.api.virtualusers.SshVirtualUsers
import com.atlassian.performance.tools.infrastructure.api.virtualusers.VirtualUsers
import com.atlassian.performance.tools.virtualusers.api.TemporalRate
import com.atlassian.performance.tools.virtualusers.api.VirtualUserLoad
import com.atlassian.performance.tools.virtualusers.api.VirtualUserOptions
import com.atlassian.performance.tools.virtualusers.api.config.VirtualUserBehavior
import com.google.common.util.concurrent.ThreadFactoryBuilder
import java.util.concurrent.Executors

/**
 * Rewrite [VirtualUserLoad] ourselves,
 * because old [MulticastVirtualUsers] cannot possibly now about the new [VirtualUserLoad.maxOverallLoad].
 */
class ThrottlingMulticastVirtualUsers(
    private val buggedMulticastVus: MulticastVirtualUsers<SshVirtualUsers>
) : VirtualUsers by buggedMulticastVus {

    private val nodes = buggedMulticastVus.nodes

    override fun applyLoad(
        options: VirtualUserOptions
    ) {
        val nodeCount = nodes.size
        val load = options.behavior.load
        val virtualUsers = load.virtualUsers
        if (nodeCount > virtualUsers) {
            throw Exception("$virtualUsers virtual users are not enough to spread into $nodeCount nodes")
        }
        val vusPerNode = virtualUsers / nodeCount
        val rampPerNode = load.ramp.dividedBy(nodeCount.toLong())
        multicast("apply load") { node, index ->
            node.applyLoad(
                VirtualUserOptions(
                    target = options.target,
                    behavior = VirtualUserBehavior.Builder(options.behavior)
                        .load(
                            VirtualUserLoad.Builder()
                                .virtualUsers(vusPerNode)
                                .hold(load.hold + rampPerNode.multipliedBy(index))
                                .ramp(rampPerNode)
                                .flat(load.flat + rampPerNode.multipliedBy(nodeCount - index - 1))
                                .maxOverallLoad(TemporalRate(
                                    load.maxOverallLoad.change / nodeCount,
                                    load.maxOverallLoad.time
                                ))
                                .build()
                        )
                        .let { if (index > 0) it.skipSetup(true) else it }
                        .build()
                )
            )
        }
    }

    /**
     * @param label Labels the [operation].
     * @param operation Operates on a the given node, indexed from 0.
     */
    private fun multicast(
        label: String,
        operation: (VirtualUsers, Long) -> Unit
    ) {
        val executor = Executors.newFixedThreadPool(
            nodes.size,
            ThreadFactoryBuilder()
                .setNameFormat("multicast-virtual-users-$label-thread-%d")
                .build()
        )
        nodes
            .mapIndexed { index, node ->
                executor.submitWithLogContext("$label $node") {
                    try {
                        operation(node, index.toLong())
                    } catch (e: Exception) {
                        throw Exception("$label failed on $node", e)
                    }
                }
            }
            .forEach { it.get() }
        executor.shutdownNow()
    }
}
