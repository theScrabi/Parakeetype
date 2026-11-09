package org.schabi.parakeetype.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Building blocks for pages styled like a sub page of the Android 16/17 Settings app
 * (Parakeetype is reachable from there): groups of rounded items on the surfaceContainer
 * background, primary-coloured section headers, and plain icons in the summary colour.
 */

/** Outer corners of a group of settings items. */
private val GROUP_CORNER = 24.dp

/** Corners between two items of the same group. */
private val ITEM_CORNER = 4.dp

/** Shape of item [index] of a group of [count] items: large corners only at the group's ends. */
internal fun groupItemShape(index: Int, count: Int): Shape {
    val top = if (index == 0) GROUP_CORNER else ITEM_CORNER
    val bottom = if (index == count - 1) GROUP_CORNER else ITEM_CORNER
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Scrolling page content with the Settings app's side margins. */
@Composable
internal fun SettingsColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        content = content,
    )
}

/** A section title above a group: small, in the primary colour. */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Explanatory text on the page background, e.g. an introduction above the first group. */
@Composable
internal fun PageText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Space between two groups that have no header. */
@Composable
internal fun GroupSpacer() {
    Spacer(Modifier.height(16.dp))
}

/** A group of settings items separated by small gaps; each item gets its [Shape]. */
@Composable
internal fun SettingsGroup(items: List<@Composable (Shape) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item -> item(groupItemShape(index, items.size)) }
    }
}

/** The background of one item of a group, for content that isn't a plain row. */
@Composable
internal fun SettingsCard(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The title of a settings item. */
@Composable
internal fun ItemTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The summary below a settings item's title. */
@Composable
internal fun ItemSummary(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One row of a [SettingsGroup]: an optional plain icon (in the summary colour, as on the
 * Settings app's sub pages), a title, an optional summary and an optional [trailing] widget.
 * [rowModifier] makes the whole row clickable or toggleable.
 */
@Composable
internal fun SettingsItem(
    title: String,
    shape: Shape,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
    rowModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .then(
                if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                else Modifier
            )
            .then(rowModifier)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            ItemTitle(title)
            if (subtitle != null) ItemSummary(subtitle)
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** A settings item with a switch; the whole row toggles it. */
@Composable
internal fun SwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
) {
    SettingsItem(
        title = title,
        subtitle = subtitle,
        shape = shape,
        rowModifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/**
 * Radio buttons choosing one of [options] (value to label), one per row as in the Settings
 * app, so long labels wrap within their own row. The whole row selects its option.
 */
@Composable
internal fun RadioChoice(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(
                        selected = selected == value,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    )
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == value, onClick = null)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** A settings item choosing one of [options] with radio buttons below its title and summary. */
@Composable
internal fun ChoiceItem(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    shape: Shape,
) {
    SettingsCard(shape) {
        Column {
            ItemTitle(title)
            ItemSummary(subtitle)
        }
        RadioChoice(options, selected, onSelect)
    }
}
