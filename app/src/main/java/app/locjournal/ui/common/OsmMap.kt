package app.locjournal.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.locjournal.geo.GeoUtils
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

data class MapMarker(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val title: String,
    /** Short text drawn inside the pin (e.g. an order number). */
    val label: String? = null,
    val color: Int = 0xFF1E5B8C.toInt(),
)

data class MapCircle(val lat: Double, val lon: Double, val radiusMeters: Double)

/**
 * OpenStreetMap view (osmdroid, no API key). The camera fits the content whenever [cameraKey] changes,
 * and otherwise stays where you panned it.
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    markers: List<MapMarker> = emptyList(),
    path: List<MapMarker> = emptyList(),
    circle: MapCircle? = null,
    cameraKey: Any? = null,
    defaultZoom: Double = 17.0,
    onMapTap: ((Double, Double) -> Unit)? = null,
    onMarkerClick: ((MapMarker) -> Unit)? = null,
) {
    val context = LocalContext.current
    val tap by rememberUpdatedState(onMapTap)
    val markerClick by rememberUpdatedState(onMarkerClick)
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 19.5
            controller.setZoom(defaultZoom)
            isTilesScaledToDpi = true
        }
    }
    val cameraState = remember { arrayOf<Any?>(Unit) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { map ->
        map.overlays.clear()
        // Added first so it sits below markers: markers get their taps, empty map taps come here.
        map.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    val handler = tap ?: return false
                    handler(p.latitude, p.longitude)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }),
        )
        circle?.let { c ->
            map.overlays.add(
                Polygon(map).apply {
                    setPoints(Polygon.pointsAsCircle(GeoPoint(c.lat, c.lon), c.radiusMeters))
                    fillPaint.color = 0x331E88E5
                    outlinePaint.color = 0xFF1E88E5.toInt()
                    outlinePaint.strokeWidth = 3f
                    infoWindow = null
                    setOnClickListener { _, _, _ -> false }
                },
            )
        }
        if (path.size > 1) {
            map.overlays.add(
                Polyline(map).apply {
                    setPoints(path.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = 0xAA1E5B8C.toInt()
                    outlinePaint.strokeWidth = 6f
                    infoWindow = null
                    setOnClickListener { _, _, _ -> false }
                },
            )
        }
        markers.forEach { m ->
            map.overlays.add(
                Marker(map).apply {
                    position = GeoPoint(m.lat, m.lon)
                    title = m.title
                    icon = pinDrawable(map.context, m.label, m.color)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        markerClick?.invoke(m)
                        true
                    }
                },
            )
        }

        if (cameraState[0] != cameraKey) {
            cameraState[0] = cameraKey
            val points = markers.map { GeoPoint(it.lat, it.lon) } +
                path.map { GeoPoint(it.lat, it.lon) } +
                listOfNotNull(circle?.let { GeoPoint(it.lat, it.lon) })
            fitCamera(map, points, defaultZoom, circle?.radiusMeters)
        }
        map.invalidate()
    }
}

private fun fitCamera(map: MapView, points: List<GeoPoint>, defaultZoom: Double, circleRadius: Double?) {
    if (points.isEmpty()) return
    val first = points.first()
    val spread = points.maxOf { GeoUtils.distanceMeters(first.latitude, first.longitude, it.latitude, it.longitude) }
    val action = {
        if (spread < 50) {
            val zoom = if (circleRadius != null && circleRadius > 150) defaultZoom - 1.5 else defaultZoom
            map.controller.setZoom(zoom)
            map.controller.setCenter(first)
        } else {
            map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.4f), false)
        }
    }
    if (map.width > 0 && map.height > 0) action() else map.addOnFirstLayoutListener { _, _, _, _, _ -> action() }
}

private fun pinDrawable(context: Context, label: String?, color: Int): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val w = (30 * density).toInt()
    val h = (40 * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * density
    }
    val r = w / 2f - 2 * density
    val cx = w / 2f
    val cy = r + 2 * density
    // Teardrop: circle plus a triangle pointing to the anchor at the bottom center.
    val path = android.graphics.Path().apply {
        moveTo(cx - r * 0.6f, cy + r * 0.7f)
        lineTo(cx, h.toFloat() - density)
        lineTo(cx + r * 0.6f, cy + r * 0.7f)
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawCircle(cx, cy, r, fill)
    canvas.drawCircle(cx, cy, r, stroke)
    if (!label.isNullOrEmpty()) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            textSize = (if (label.length > 2) 10 else 13) * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }
    return BitmapDrawable(context.resources, bmp)
}
