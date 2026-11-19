package com.aird.feature.extras

import android.content.Context
import com.aird.data.db.LocalStore
import com.aird.data.db.StarredEntity
import com.aird.session.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object StarredRepo {
    suspend fun all(context: Context): Flow<List<StarredEntity>> {
        val user = Session.username.value
        val db = LocalStore.db(context, user) ?: return flowOf(emptyList())
        return db.starred().all()
    }

    suspend fun unstar(context: Context, messageId: String) {
        val user = Session.username.value
        val db = LocalStore.db(context, user) ?: return
        db.starred().delete(messageId)
    }
}
