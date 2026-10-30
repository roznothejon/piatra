package dev.piatra.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piatra.R

// Small pieces built from the Phosphor icons in res/drawable (see tools/phosphor.py), shared by
// the screens. An Icon takes the colour of what's around it (LocalContentColor): the accent
// colour inside a TextButton, grey when the button is disabled.

// The size of an icon next to body text or in a TextButton (Material's ButtonDefaults.IconSize).
private val SmallIcon = 18.dp

// "‹ Back" at the top left of every detail screen.
@Composable
fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Icon(painterResource(R.drawable.ph_caret_left), contentDescription = null, modifier = Modifier.size(SmallIcon))
        Text("Back")
    }
}

// An icon-only button. contentDescription: what TalkBack says, since there's no text to read.
@Composable
fun IconOnlyButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = contentDescription)
    }
}

// ✕: remove a row, or hide a message. Smaller and grey, so a column of them doesn't shout.
@Composable
fun RemoveButton(onClick: () -> Unit, contentDescription: String = "Remove") {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(R.drawable.ph_x), contentDescription = contentDescription,
            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Text with an icon in front, e.g. the bell before a reminder time. color: for the text and the
// icon together; unset, both follow the surroundings.
@Composable
fun IconText(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    // LocalTextStyle: the style around it (a TextButton's, say), as a plain Text would use.
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    iconSize: Dp = SmallIcon,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(icon), contentDescription = null, modifier = Modifier.size(iconSize),
            tint = if (color != Color.Unspecified) color else LocalContentColor.current,
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = style, color = color)
    }
}

// Text with › after it: tapping it goes somewhere ("From recording ›").
@Composable
fun ForwardText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color)
        Icon(
            painterResource(R.drawable.ph_caret_right), contentDescription = null, modifier = Modifier.size(SmallIcon),
            tint = if (color != Color.Unspecified) color else LocalContentColor.current,
        )
    }
}
