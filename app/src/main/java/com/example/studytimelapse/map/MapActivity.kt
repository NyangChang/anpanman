package com.example.studytimelapse.map

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.studytimelapse.databinding.ActivityMapBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMapBinding
    private var map: GoogleMap? = null
    private var catMarker: Marker? = null
    private var isFirstPosition = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val mapFragment = supportFragmentManager
            .findFragmentById(binding.mapFragment.id) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.uiSettings.isZoomControlsEnabled = true

        // Observe cat position
        CatRepository.position.observe(this) { pos ->
            updateCat(pos)
        }

        // Observe step count
        CatRepository.stepCount.observe(this) { steps ->
            binding.tvStepCount.text = "🐱 $steps 歩"
        }
    }

    private fun updateCat(pos: CatRepository.CatPosition) {
        val map = map ?: return
        val latLng = LatLng(pos.lat, pos.lng)
        val icon = CatMarkerFactory.create(this, sizeDp = 48)

        if (catMarker == null) {
            catMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .icon(icon)
                    .title("猫")
                    .anchor(0.5f, 0.5f)
            )
        } else {
            catMarker!!.position = latLng
            catMarker!!.setIcon(icon)
        }

        // Follow the cat; on first load zoom in close
        val zoom = if (isFirstPosition) { isFirstPosition = false; 15f } else map.cameraPosition.zoom
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, zoom))
    }
}
