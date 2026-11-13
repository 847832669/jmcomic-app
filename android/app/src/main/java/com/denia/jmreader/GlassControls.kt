package com.denia.jmreader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Keep Material input, focus and accessibility behavior above the glass backdrop. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(modifier, shape = shape,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        tintStrength = if (enabled) 0.84f else 0.36f) {
        Button(onClick, enabled = enabled, shape = shape, contentPadding = contentPadding,
            colors = colors.copy(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
            elevation = null, content = content)
    }
}

@Composable
fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(modifier, shape = shape, tintStrength = 0.4f) {
        OutlinedButton(onClick, enabled = enabled, shape = shape, border = null, content = content)
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    GlassSurface(modifier, shape = shape, tintStrength = 0.45f) {
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(),
            label = label, placeholder = placeholder, trailingIcon = trailingIcon,
            singleLine = singleLine, shape = shape, visualTransformation = visualTransformation,
            colors = colors.copy(focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent))
    }
}

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    Dialog(onDismissRequest) {
        GlassSurface(shape = RoundedCornerShape(28.dp), radius = 28.dp, prominent = true,
            tintStrength = 0.82f) {
            Column(Modifier.padding(24.dp)) {
                title?.let {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() }
                    Spacer(Modifier.height(16.dp))
                }
                text?.let {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                    Spacer(Modifier.height(20.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
