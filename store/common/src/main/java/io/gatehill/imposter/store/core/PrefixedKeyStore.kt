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
package io.gatehill.imposter.store.core

import io.gatehill.imposter.http.ExchangePhase

/**
 * A delegating [Store] wrapper that prepends a string to item keys
 * before persistence and retrieval.
 *
 * @author Pete Cornish
 */
class PrefixedKeyStore(
    private val keyPrefix: String,
    private val delegate: Store,
) : Store {
    override val storeName: String
        get() = delegate.storeName

    override val typeDescription: String
        get() = delegate.typeDescription

    override val isEphemeral: Boolean
        get() = delegate.isEphemeral

    private fun buildKey(key: String): String {
        return keyPrefix + key
    }

    override fun save(key: String, value: Any?, phase: ExchangePhase) {
        delegate.save(buildKey(key), value, phase)
    }

    override fun <T> load(key: String): T? = delegate.load(buildKey(key))

    override fun loadByKeyPrefix(keyPrefix: String): Map<String, Any?> = delegate.loadByKeyPrefix(keyPrefix)

    override fun delete(key: String) {
        delegate.delete(buildKey(key))
    }

    override fun loadAll(): Map<String, Any?> {
        // strip out key prefix
        return delegate.loadAll().entries.associate { (key, value) ->
            key.substring(keyPrefix.length) to value
        }
    }

    override fun hasItemWithKey(key: String) = delegate.hasItemWithKey(buildKey(key))

    override fun count() = delegate.count()
}
