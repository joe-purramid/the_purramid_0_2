package com.example.purramid.thepurramid.clock.ui

import android.animation.ValueAnimator
import android.animation.TypeEvaluator
import android.view.animation.AccelerateDecelerateInterpolator
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
import kotlinx.coroutines.flow.collectLatest
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
import com.google.android.material.snackbar.Snackbar

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

    // --- City Pin Management ---
    private val cityPinNodes = mutableListOf<Node>()
    
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
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { uiState ->
                // Update UI elements based on state
                uiState.activeTimeZoneInfo?.let { info ->
                    cityNorthernTextView.text = info.northernCity
                    citySouthernTextView.text = info.southernCity
                    utcOffsetTextView.text = info.utcOffsetString
                }
                
                // Update city pins when timezone changes
                updateCityPins(uiState.activeTimeZoneId)
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
        ValueAnimator.ofObject(RotationEvaluator(), start, end).apply {
            duration = 500 // ms
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                globeNode?.rotation = animation.animatedValue as Rotation
            }
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
        Snackbar.make(sceneView, message, Snackbar.LENGTH_LONG).show()
    }

    // --- Lifecycle is handled by SceneView ---

    private fun updateCityPins(timeZoneId: String?) {
        // Clear existing pins
        clearCityPins()
        
        if (timeZoneId == null) return
        
        // Load cities for the active timezone
        lifecycleScope.launch {
            try {
                val cities = viewModel.repository.getCitiesForTimeZone(timeZoneId)
                val northernCities = cities.filter { it.latitude > 0 }.sortedBy { it.latitude }.reversed().take(2)
                val southernCities = cities.filter { it.latitude < 0 }.sortedBy { it.latitude }.take(2)
                
                // Create pins for northern cities
                northernCities.forEach { city ->
                    createCityPin(city, isNorthern = true)
                }
                
                // Create pins for southern cities
                southernCities.forEach { city ->
                    createCityPin(city, isNorthern = false)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading city pins for $timeZoneId", e)
            }
        }
    }
    
    private fun createCityPin(city: CityData, isNorthern: Boolean) {
        lifecycleScope.launch {
            try {
                // Create a simple sphere for the city pin
                val engine = sceneView.engine
                val pinRadius = 0.02f // Small pin size
                val pinColor = if (isNorthern) SceneViewColor(0xFF0000FF) else SceneViewColor(0xFF00FF00) // Blue for north, green for south
                
                val material = materialLoader.createColorInstance(
                    color = pinColor,
                    isMetallic = 0.0f,
                    roughness = 0.5f,
                    reflectance = 0.0f
                )
                
                // Create sphere geometry (simplified)
                val renderableEntity = engine.entityManager.create()
                val bounds = Box(0f, 0f, 0f, pinRadius, pinRadius, pinRadius)
                
                RenderableManager.Builder(1)
                    .boundingBox(bounds)
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, createSphereGeometry(pinRadius))
                    .material(0, material)
                    .culling(false)
                    .build(engine, renderableEntity)
                
                // Position pin on globe surface
                val pinPosition = latLonToFloat3(city.latitude, city.longitude, EARTH_RADIUS + 0.01f) // Slightly above surface
                val pinNode = Node(engine, renderableEntity).apply {
                    position = Position(pinPosition.x, pinPosition.y, pinPosition.z)
                    tag = "city_pin_${city.name}"
                }
                
                pinNode.setParent(overlayParentNode)
                cityPinNodes.add(pinNode)
                
                Log.d(TAG, "Created city pin for ${city.name} at (${city.latitude}, ${city.longitude})")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating city pin for ${city.name}", e)
            }
        }
    }
    
    private fun clearCityPins() {
        cityPinNodes.forEach { it.destroy() }
        cityPinNodes.clear()
    }
    
    private fun createSphereGeometry(radius: Float): Pair<VertexBuffer, IndexBuffer> {
        // Simplified sphere geometry creation
        val segments = 8
        val vertices = mutableListOf<Float3>()
        val indices = mutableListOf<Int>()
        
        // Generate sphere vertices
        for (lat in 0..segments) {
            val theta = lat * Math.PI / segments
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)
            
            for (lon in 0..segments) {
                val phi = lon * 2 * Math.PI / segments
                val sinPhi = sin(phi)
                val cosPhi = cos(phi)
                
                val x = (radius * cosPhi * sinTheta).toFloat()
                val y = (radius * cosTheta).toFloat()
                val z = (radius * sinPhi * sinTheta).toFloat()
                
                vertices.add(Float3(x, y, z))
            }
        }
        
        // Generate indices for triangles
        for (lat in 0 until segments) {
            for (lon in 0 until segments) {
                val current = lat * (segments + 1) + lon
                val next = current + segments + 1
                
                indices.add(current)
                indices.add(next)
                indices.add(current + 1)
                
                indices.add(next)
                indices.add(next + 1)
                indices.add(current + 1)
            }
        }
        
        val engine = sceneView.engine
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertices.size)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 0)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, Float3.toFloatBuffer(vertices))
        
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, IntArray(indices.size) { indices[it] }.toBuffer())
        
        return Pair(vertexBuffer, indexBuffer)
    }
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

// --- Animation Support ---
class RotationEvaluator : TypeEvaluator<Rotation> {
    override fun evaluate(fraction: Float, startValue: Rotation, endValue: Rotation): Rotation {
        // Interpolate each rotation component (x, y, z)
        val startX = startValue.x
        val startY = startValue.y
        val startZ = startValue.z
        
        val endX = endValue.x
        val endY = endValue.y
        val endZ = endValue.z
        
        // Handle Y-axis wrap-around (longitude)
        val deltaY = endY - startY
        val adjustedDeltaY = when {
            deltaY > 180f -> deltaY - 360f
            deltaY < -180f -> deltaY + 360f
            else -> deltaY
        }
        
        val interpolatedX = startX + (endX - startX) * fraction
        val interpolatedY = startY + adjustedDeltaY * fraction
        val interpolatedZ = startZ + (endZ - startZ) * fraction
        
        return Rotation(
            x = interpolatedX,
            y = interpolatedY,
            z = interpolatedZ
        )
    }
}