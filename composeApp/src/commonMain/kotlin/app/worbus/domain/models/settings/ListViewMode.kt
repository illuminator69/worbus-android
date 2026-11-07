package app.worbus.domain.models.settings

import androidx.compose.ui.graphics.vector.ImageVector
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_list_view_mode_grid
import worbus.composeapp.generated.resources.option_list_view_mode_list
import org.jetbrains.compose.resources.StringResource
import app.worbus.icons.Icons
import app.worbus.icons.outlined.Grid
import app.worbus.icons.outlined.List

enum class ListViewMode(val displayName: StringResource, val icon: ImageVector) {
	Grid(Res.string.option_list_view_mode_grid, Icons.Outlined.Grid),
	List(Res.string.option_list_view_mode_list, Icons.Outlined.List)
}
