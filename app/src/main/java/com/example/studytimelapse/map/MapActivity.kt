package com.example.studytimelapse.map

import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity
import com.example.studytimelapse.databinding.ActivityMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var mapView: MapView
    private var catMarker: Marker? = null
    private var isFirstPosition = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        binding.btnBack.setOnClickListener { finish() }

        CatRepository.position.observe(this) { pos -> updateCat(pos) }
        CatRepository.stepCount.observe(this) { steps ->
            binding.tvStepCount.text = "🐱 $steps 歩"
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun updateCat(pos: CatRepository.CatPosition) {
        val geoPoint = GeoPoint(pos.lat, pos.lng)

        if (catMarker == null) {
            catMarker = Marker(mapView).apply {
                position = geoPoint
                icon = CatMarkerFactory.create(this@MapActivity, sizeDp = 48)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "猫"
            }
            mapView.overlays.add(catMarker)
        } else {
            catMarker!!.position = geoPoint
            catMarker!!.icon = CatMarkerFactory.create(this, sizeDp = 48)
        }

        val zoom = if (isFirstPosition) { isFirstPosition = false; 15.0 } else mapView.zoomLevelDouble
        mapView.controller.animateTo(geoPoint, zoom, 500L)
        mapView.invalidate()
    }
}
