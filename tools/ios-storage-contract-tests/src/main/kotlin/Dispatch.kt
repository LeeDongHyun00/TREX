package platform.darwin

fun dispatch_get_main_queue() = Unit
fun dispatch_async(queue: Unit, block: () -> Unit) = block()
