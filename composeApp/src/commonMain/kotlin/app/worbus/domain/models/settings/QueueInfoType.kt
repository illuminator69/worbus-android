package app.worbus.domain.models.settings

import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_queue_info_type_full
import worbus.composeapp.generated.resources.option_queue_info_type_remining
import org.jetbrains.compose.resources.StringResource

enum class QueueInfoType(val displayName: StringResource) {
	Full(Res.string.option_queue_info_type_full),
	Remaining(Res.string.option_queue_info_type_remining);
}
