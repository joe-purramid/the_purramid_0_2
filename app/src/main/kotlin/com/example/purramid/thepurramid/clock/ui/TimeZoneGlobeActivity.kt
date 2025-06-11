package com.example.purramid.thepurramid.clock.ui

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.purramid.thepurramid.R
import com.google.android.filament.Box
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.utils.Float3 // Using Filament types directly
import dagger.hilt.android.AndroidEntryPoint
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.utils.Color as SceneViewColor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.locationtech.jts.geom.Polygon
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.cos
import kotlin.math.sin

// Import ViewModel and State classes from this package
import com.example.purramid.thepurramid.clock.ui.TimeZoneGlobeUiState
import com.example.purramid.thepurramid.clock.ui.TimeZoneGlobeViewModel
import com.example.purramid.thepurramid.clock.ui.TimeZoneOverlayInfo
import com.example.purramid.thepurramid.clock.ui.RotationDirection // Import Enum

@AndroidEntryPoint // Enable Hilt Injection
class TimeZoneGlobeActivity : AppCompatActivity(R.layout.activity_time_zone_globe) { // Use constructor injection for layout

    private val TAG = "TimeZoneGlobeActivity"
    // Assuming assets are directly in app/src/main/assets/
    private val GLOBE_MODEL_ASSET_PATH = "scene.gltf"
    private val EARTH_RADIUS = 0.7f // Adjust scale to match your model's desired size
    private val OVERLAY_RADIUS_FACTOR = 1.005f // Slightly above surface to prevent Z-fighting
    private val ROTATION_FACTOR = 0.15f // Touch rotation sensitivity
    // BUTTON_ROTATION_AMOUNT_DEGREES is no longer used directly here

    // Inject ViewModel
    private val viewModel: TimeZoneGlobeViewModel by viewModels()

    // Views
    private lateinit var sceneView: SceneView
    private lateinit var rotateLeftButton: Button
    private lateinit var rotateRightButton: Button
    private lateinit var resetButton: Button
    private lateinit var cityNorthernTextView: TextView
    private lateinit var citySouthernTextView: TextView
    private lateinit var utcOffsetTextView: TextView

    // Scene Objects
    private lateinit var modelLoader: ModelLoader
    private lateinit var materialLoader: MaterialLoader
    private var globeNode: ModelNode? = null
    private var overlayParentNode: Node? = null // Parent for overlays, attached to globeNode

    // Material cache
    private val materialCache = mutableMapOf<Int, CompletableDeferred<MaterialInstance>>()

    // Touch rotation state
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind Views
        sceneView = findViewById(R.id.sceneView)
        rotateLeftButton = findViewById(R.id.rotateLeftButton)
        rotateRightButton = findViewById(R.id.rotateRightButton)
        resetButton = findViewById(R.id.resetButton)
        cityNorthernTextView = findViewById(R.id.cityNorthernTextView)
        citySouthernTextView = findViewById(R.id.citySouthernTextView)
        utcOffsetTextView = findViewById(R.id.utcOffsetTextView)

        // Initialize SceneView Loaders
        modelLoader = ModelLoader(sceneView.engine, this)
        materialLoader = MaterialLoader(sceneView.engine)

        // Configure SceneView Camera (optional, adjust defaults)
        sceneView.cameraNode.position = Position(x = 0.0f, y = 0.0f, z = 2.5f) // Adjust distance

        // Start loading the globe model
        loadGlobeModel()

        // Setup interaction listeners
        setupTouchListener() // Includes drag rotation and tap selection
        setupButtonListeners() // Includes new ViewModel calls
        resetButton.setOnClickListener {
            viewModel.resetRotation() // Call the ViewModel's reset function
        }

        // Observe state changes from ViewModel
        observeViewModel()
    }

    private fun loadGlobeModel() {
        lifecycleScope.launch { // Use lifecycleScope for coroutine
            try {
                val model = modelLoader.loadModel(GLOBE_MODEL_ASSET_PATH)
                if (model == null || model.asset == null) {
                    handleError("Failed to load model: $GLOBE_MODEL_ASSET_PATH")
                    return@launch
                }
                val modelInstance = model.instance ?: model.createInstance("DefaultGlobe")
                globeNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = EARTH_RADIUS
                ).apply {
                    viewModel.uiState.value?.let { state ->
                        rotation = state.currentRotation
                    }
                    isPositionEditable = false
                    isRotationEditable = false
                    isScaleEditable = false
                }
                sceneView.addChild(globeNode!!)
                overlayParentNode = Node(sceneView.engine).apply {
                    setParent(globeNode)
                }
                Log.d(TAG, "Purramid Globe model loaded and added.") // Added brand name
                viewModel.uiState.value?.let { state ->
                    if (!state.isLoading && state.timeZoneOverlays.isNotEmpty()) {
                        createOrUpdateOverlays(state.timeZoneOverlays)
                    }
                }
            } catch (e: Exception) {
                handleError("Error loading globe model", e)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            updateUiTexts(state)
            if (globeNode != null && overlayParentNode != null) {
                if (state.isLoading) {
                    clearOverlays()
                } else if (state.error != null) {
                    handleError(state.error)
                    clearOverlays()
                } else {
                    globeNode?.rotation = state.currentRotation // Apply rotation from ViewModel
                    createOrUpdateOverlays(state.timeZoneOverlays)
                }
            }

            // Check if the target rotation is new and different from the current node rotation
            val currentVisualRotation = globeNode?.rotation // Get actual current rotation
            if (state.targetRotation != null && state.targetRotation != currentVisualRotation) {
                // *** Start Animation ***
                animateGlobeRotation(currentVisualRotation ?: state.currentRotation, state.targetRotation)
                // Optionally update ViewModel's currentRotation once animation is triggered or finished
                // viewModel.syncCurrentRotation(state.targetRotation) // Example method needed in ViewModel
            } else if (state.targetRotation == null && currentVisualRotation != state.currentRotation) {
                // If target is null, snap to the logical currentRotation (e.g., after reset or drag)
                globeNode?.rotation = state.currentRotation
            }
        }
    }

    private fun updateUiTexts(state: TimeZoneGlobeUiState) {
        state.activeTimeZoneInfo?.let { info ->
            cityNorthernTextView.text = info.northernCity
            citySouthernTextView.text = info.southernCity
            utcOffsetTextView.text = info.utcOffsetString
        } ?: run {
            cityNorthernTextView.text = ""
            citySouthernTextView.text = ""
            utcOffsetTextView.text = getString(R.string.timezone_placeholder) // Use resource
        }
    }

    // --- Overlay Creation ---
    private fun createOrUpdateOverlays(overlayInfos: List<TimeZoneOverlayInfo>) {
        if (overlayParentNode == null) return
        clearOverlays()
        overlayInfos.forEach { info ->
            val materialDeferred = getOrCreateMaterial(info.color)
            info.polygons.forEach { polygon ->
                createPolygonRenderable(polygon, materialDeferred, info.tzid)
            }
        }
    }

    private fun getOrCreateMaterial(colorArgb: Int): CompletableDeferred<MaterialInstance> {
        return materialCache.getOrPut(colorArgb) {
            val deferred = CompletableDeferred<MaterialInstance>()
            lifecycleScope.launch {
                try {
                    val color = SceneViewColor(colorArgb)
                    val material = materialLoader.createColorInstance(
                        color = color, isMetallic = 0.0f, roughness = 1.0f, reflectance = 0.0f
                    )
                    material.material.blendingMode = MaterialLoader.BlendingMode.TRANSPARENT
                    material.setParameter("baseColorFactor", color)
                    deferred.complete(material)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create material instance for color $colorArgb", e)
                    deferred.completeExceptionally(e)
                }
            }
            deferred
        }
    }

    private fun animateGlobeRotation(start: Rotation, end: Rotation) {
        // Use ObjectAnimator or compose animation to animate globeNode.rotation
        // from 'start' to 'end' over a specific duration.
        Log.d(TAG, "Animating rotation from $start to $end")
        // Example using ObjectAnimator (might need a TypeEvaluator for Rotation)
        ObjectAnimator.ofObject(globeNode, "rotation", RotationEvaluator(), start, end).apply {
            duration = 500 // ms
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun clearOverlays() {
        overlayParentNode?.children?.toList()?.forEach { it.destroy() }
    }

    private fun createPolygonRenderable(
        polygon: Polygon,
        materialDeferred: CompletableDeferred<MaterialInstance>,
        tzId: String
    ) {
        lifecycleScope.launch {
            try {
                val materialInstance = materialDeferred.await()
                val engine = sceneView.engine
                val geometry = triangulatePolygon(polygon) ?: return@launch
                val (vertices, indices) = geometry

                val vertexBuffer = VertexBuffer.Builder()
                    .bufferCount(1).vertexCount(vertices.size)
                    .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 0)
                    .build(engine)
                vertexBuffer.setBufferAt(engine, 0, Float3.toFloatBuffer(vertices))

                val indexBuffer = IndexBuffer.Builder()
                    .indexCount(indices.size).bufferType(IndexBuffer.Builder.IndexType.UINT)
                    .build(engine)
                indexBuffer.setBuffer(engine, IntArray(indices.size) { indices[it] }.toBuffer())

                val bounds = calculateBounds(vertices)
                val renderableEntity = engine.entityManager.create()
                RenderableManager.Builder(1)
                    .boundingBox(bounds)
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, indices.size)
                    .material(0, materialInstance)
                    .culling(false)
                    .build(engine, renderableEntity)

                val node = Node(engine, renderableEntity).apply {
                    tag = tzId // Store tzid for tap detection
                }
                node.setParent(overlayParentNode)

            } catch (e: Exception) {
                Log.e(TAG, "Error creating overlay renderable for $tzId", e)
                // Handle error, potentially destroy awaited material if needed
            }
        }
    }

    // Helper for triangulation (Simple Fan Example)
    private fun triangulatePolygon(polygon: Polygon): Pair<List<Float3>, List<Int>>? {
        try {
            if (polygon.exteriorRing.coordinates.size < 4) return null
            val vertices = mutableListOf<Float3>()
            val indices = mutableListOf<Int>()
            val overlayRadius = EARTH_RADIUS * OVERLAY_RADIUS_FACTOR
            val centroid = polygon.centroid
            vertices.add(latLonToFloat3(centroid.y, centroid.x, overlayRadius))
            val centroidIndex = 0
            for (i in 0 until polygon.exteriorRing.coordinates.size - 1) {
                val coord = polygon.exteriorRing.coordinates[i]
                vertices.add(latLonToFloat3(coord.y, coord.x, overlayRadius))
                val currentIndex = i + 1
                val nextIndex = if (i == polygon.exteriorRing.coordinates.size - 2) 1 else currentIndex + 1
                indices.add(centroidIndex); indices.add(currentIndex); indices.add(nextIndex)
            }
            return Pair(vertices, indices)
        } catch (e: Exception) {
            Log.e(TAG, "Error during triangulation", e); return null
        }
    }

    // Helper to calculate bounding box
    private fun calculateBounds(vertices: List<Float3>): Box {
        if (vertices.isEmpty()) return Box(0f, 0f, 0f, 0.1f, 0.1f, 0.1f) // Min bounds
        var minX = vertices[0].x; var minY = vertices[0].y; var minZ = vertices[0].z
        var maxX = vertices[0].x; var maxY = vertices[0].y; var maxZ = vertices[0].z
        for (i in 1 until vertices.size) {
            minX = minOf(minX, vertices[i].x); minY = minOf(minY, vertices[i].y); minZ = minOf(minZ, vertices[i].z)
            maxX = maxOf(maxX, vertices[i].x); maxY = maxOf(maxY, vertices[i].y); maxZ = maxOf(maxZ, vertices[i].z)
        }
        val halfExtentX = (maxX - minX) / 2f
        val halfExtentY = (maxY - minY) / 2f
        val halfExtentZ = (maxZ - minZ) / 2f
        // Ensure halfExtents are not zero or negative if geometry is flat/a point
        val minHalfExtent = 0.001f
        return Box(
            (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,
            maxOf(minHalfExtent, halfExtentX), maxOf(minHalfExtent, halfExtentY), maxOf(minHalfExtent, halfExtentZ)
        )
    }

    // Helper to convert Lat/Lon to Filament Float3
    private fun latLonToFloat3(lat: Double, lon: Double, radius: Float): Float3 {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val x = radius * cos(latRad) * cos(lonRad)
        val y = radius * sin(latRad)
        val z = radius * cos(latRad) * sin(lonRad) * -1.0
        return Float3(x.toFloat(), y.toFloat(), z.toFloat())
    }
    // --- End Overlay Creation ---


    // --- User Interaction ---
    private fun setupTouchListener() {
        sceneView.cameraManipulator.enabled = false // Disable built-in manipulator

        // Handle drag gestures for rotation
        sceneView.setOnTouchListener { _, event ->
            if (globeNode == null) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    val currentRot = globeNode!!.rotation
                    val deltaYaw = -dx * ROTATION_FACTOR * 2
                    val deltaPitch = -dy * ROTATION_FACTOR * 2
                    val newPitch = (currentRot.x + deltaPitch).coerceIn(-89f, 89f)
                    val newRotation = Rotation(x = newPitch, y = (currentRot.y + deltaYaw) % 360, z = currentRot.z)
                    viewModel.updateRotation(newRotation) // Update state via ViewModel
                    lastX = event.x; lastY = event.y; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { true }
                else -> false
            }
        }

        // Handle tap gestures for selection
        sceneView.onTap = { motionEvent, hitResult ->
            val hitNode = hitResult?.node
            if (hitNode != null && hitNode.parent == overlayParentNode && hitNode.tag is String) {
                val tappedTzId = hitNode.tag as String
                Log.d(TAG, "Tapped on time zone: $tappedTzId")
                viewModel.setActiveTimeZone(tappedTzId)
            } else {
                Log.d(TAG, "Tap detected, but not on a tagged overlay node.")
            }
        }
    }

    private fun setupButtonListeners() {
        rotateLeftButton.setOnClickListener { viewModel.rotateToAdjacentZone(RotationDirection.LEFT) }
        rotateRightButton.setOnClickListener { viewModel.rotateToAdjacentZone(RotationDirection.RIGHT) }
        resetButton.setOnClickListener { viewModel.updateRotation(Rotation(0f, 0f, 0f)) }
    }
    // --- End User Interaction ---

    // --- Utility ---
    private fun handleError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // --- Lifecycle is handled by SceneView ---
}

// --- Buffer Utils (Outside Activity) ---
fun Float3.Companion.toFloatBuffer(list: List<Float3>): FloatBuffer {
    val buffer = java.nio.ByteBuffer.allocateDirect(list.size * 3 * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
    list.forEach { buffer.put(it.toFloatArray()) }
    buffer.rewind(); return buffer
}
fun IntArray.toBuffer(): IntBuffer {
    val buffer = java.nio.ByteBuffer.allocateDirect(this.size * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
    buffer.put(this); buffer.rewind(); return buffer
}