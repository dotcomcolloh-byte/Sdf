package com.vidtubehub.video.app.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun icon(name:String, block:PathBuilder.()->Unit)=ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply{path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=2f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,pathBuilder=block)}.build()
val HomeIcon get()=icon("Home"){moveTo(3f,10f);lineTo(12f,3f);lineTo(21f,10f);lineTo(21f,21f);lineTo(3f,21f);close();moveTo(9f,21f);lineTo(9f,12f);lineTo(15f,12f);lineTo(15f,21f)}
val HistoryIcon get()=icon("History"){moveTo(3f,12f);arcTo(9f,9f,0f,false,true,21f,12f);arcTo(9f,9f,0f,false,true,3f,12f);moveTo(12f,7f);lineTo(12f,12f);lineTo(15f,14f)}
val DownloadIcon get()=icon("Download"){moveTo(12f,3f);lineTo(12f,15f);moveTo(7f,10f);lineTo(12f,15f);lineTo(17f,10f);moveTo(5f,21f);lineTo(19f,21f)}
val SettingsIcon get()=icon("Settings"){moveTo(12f,3f);lineTo(14f,7f);lineTo(18f,6f);lineTo(19f,10f);lineTo(22f,12f);lineTo(19f,14f);lineTo(18f,18f);lineTo(14f,17f);lineTo(12f,21f);lineTo(10f,17f);lineTo(6f,18f);lineTo(5f,14f);lineTo(2f,12f);lineTo(5f,10f);lineTo(6f,6f);lineTo(10f,7f);close();moveTo(12f,9f);arcTo(3f,3f,0f,false,true,12f,15f);arcTo(3f,3f,0f,false,true,12f,9f)}
val SearchIcon get()=icon("Search"){moveTo(11f,4f);arcTo(7f,7f,0f,false,true,11f,18f);arcTo(7f,7f,0f,false,true,11f,4f);moveTo(16f,16f);lineTo(21f,21f)}
val PlayIcon get()=icon("Play"){moveTo(8f,5f);lineTo(19f,12f);lineTo(8f,19f);close()}
val PauseIcon get()=icon("Pause"){moveTo(8f,5f);lineTo(8f,19f);moveTo(16f,5f);lineTo(16f,19f)}
val FlameIcon get()=icon("Flame"){moveTo(12f,22f);arcTo(7f,7f,0f,false,true,6f,15f);curveTo(6f,11f,10f,9f,10f,3f);curveTo(15f,7f,20f,10f,18f,16f);arcTo(6f,6f,0f,false,true,12f,22f)}
val GamepadIcon get()=icon("Gamepad"){moveTo(6f,11f);lineTo(4f,17f);arcTo(3f,3f,0f,false,true,10f,19f);lineTo(12f,16f);lineTo(14f,19f);arcTo(3f,3f,0f,false,true,20f,17f);lineTo(18f,11f);arcTo(6f,6f,0f,false,false,6f,11f);moveTo(8f,11f);lineTo(8f,14f);moveTo(6f,12f);lineTo(10f,12f);moveTo(16f,13f);lineTo(16f,13f)}
val MusicIcon get()=icon("Music"){moveTo(9f,18f);arcTo(3f,3f,0f,false,true,3f,18f);arcTo(3f,3f,0f,false,true,9f,18f);moveTo(9f,18f);lineTo(9f,5f);lineTo(20f,3f);lineTo(20f,15f);moveTo(20f,15f);arcTo(3f,3f,0f,false,true,14f,15f);arcTo(3f,3f,0f,false,true,20f,15f)}
val MovieIcon get()=icon("Clapperboard"){moveTo(3f,5f);lineTo(21f,5f);lineTo(21f,19f);lineTo(3f,19f);close();moveTo(3f,10f);lineTo(21f,10f);moveTo(7f,5f);lineTo(10f,10f);moveTo(13f,5f);lineTo(16f,10f)}
