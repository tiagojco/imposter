/*
 * Copyright (c) 2016-2021.
 *
 * This file is part of Imposter.
 *
 * "Commons Clause" License Condition v1.0
 *
 * The Software is provided to you by the Licensor under the License, as
 * defined below, subject to the following condition.
 *
 * Without limiting other conditions in the License, the grant of rights
 * under the License will not include, and the License does not grant to
 * you, the right to Sell the Software.
 *
 * For purposes of the foregoing, "Sell" means practicing any or all of
 * the rights granted to you under the License to provide to third parties,
 * for a fee or other consideration (including without limitation fees for
 * hosting or consulting/support services related to the Software), a
 * product or service whose value derives, entirely or substantially, from
 * the functionality of the Software. Any license notice or attribution
 * required by the License must also include this Commons Clause License
 * Condition notice.
 *
 * Software: Imposter
 *
 * License: GNU Lesser General Public License version 3
 *
 * Licensor: Peter Cornish
 *
 * Imposter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Imposter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Imposter.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.gatehill.imposter.store.dynamodb

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.google.inject.Inject
import io.gatehill.imposter.plugin.Plugin
import io.gatehill.imposter.plugin.PluginInfo
import io.gatehill.imposter.plugin.RequireModules
import io.gatehill.imposter.service.DeferredOperationService
import io.gatehill.imposter.store.core.Store
import io.gatehill.imposter.store.dynamodb.config.Settings
import io.gatehill.imposter.store.factory.AbstractStoreFactory
import org.apache.logging.log4j.LogManager

/**
 * @author Pete Cornish
 */
@PluginInfo("store-dynamodb")
@RequireModules(DynamoDBStoreModule::class)
class DynamoDBStoreFactoryImpl @Inject constructor(
    private val deferredOperationService: DeferredOperationService,
) : AbstractStoreFactory(deferredOperationService), Plugin {
    private val ddb: AmazonDynamoDB
    private val logger = LogManager.getLogger(DynamoDBStore::class.java)

    init {
        val builder = AmazonDynamoDBClientBuilder.standard()
        Settings.dynamoDbApiEndpoint?.let {
            val endpointConfig = AwsClientBuilder.EndpointConfiguration(Settings.dynamoDbApiEndpoint, Settings.dynamoDbRegion)
            builder.withEndpointConfiguration(endpointConfig)
        } ?: run {
            builder.withRegion(Settings.dynamoDbRegion)
        }
        Settings.awsCredentials?.let {
            builder.withCredentials(AWSStaticCredentialsProvider(it))
        }
        ddb = builder.build()
    }

    override fun buildNewStore(storeName: String): Store {
        return DynamoDBStore(deferredOperationService, storeName, ddb, Settings.tableName)
    }

    override fun clearStore(storeName: String, ephemeral: Boolean) {
        if (!ephemeral) {
            logger.info("Deleting all items from store: $storeName in table: ${Settings.tableName}")
            val store = buildNewStore(storeName)
            store.loadAll().onEach { store.delete(it.key) }
        }
        super.clearStore(storeName, ephemeral)
    }
}
