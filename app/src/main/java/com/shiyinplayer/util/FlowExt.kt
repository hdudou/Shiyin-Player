package com.shiyinplayer.util

import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.flowOn

/** 在指定协程上下文上切换 Flow 执行（便于注入 DispatcherProvider）。 */
fun <T> Flow<T>.flowOnCtx(ctx: CoroutineContext): Flow<T> = flowOn(ctx)
