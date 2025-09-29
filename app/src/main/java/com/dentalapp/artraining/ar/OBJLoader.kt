package com.dentalapp.artraining.ar

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object OBJLoader {

    private const val TAG = "OBJLoader"

    data class OBJModel(
        val vertices: FloatArray,
        val normals: FloatArray,
        val texCoords: FloatArray,
        val indices: IntArray,
        val bounds: BoundingBox
    ) {
        data class BoundingBox(
            val minX: Float, val maxX: Float,
            val minY: Float, val maxY: Float,
            val minZ: Float, val maxZ: Float
        ) {
            fun centerX() = (minX + maxX) / 2f
            fun centerY() = (minY + maxY) / 2f
            fun centerZ() = (minZ + maxZ) / 2f
            fun sizeX() = maxX - minX
            fun sizeY() = maxY - minY
            fun sizeZ() = maxZ - minZ
        }
    }

    fun loadOBJ(inputStream: InputStream): OBJModel {
        val vertices = mutableListOf<Triple<Float, Float, Float>>()
        val normals = mutableListOf<Triple<Float, Float, Float>>()
        val texCoords = mutableListOf<Pair<Float, Float>>()
        val faces = mutableListOf<Face>()

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = Float.MIN_VALUE

        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { parseLine(it, vertices, normals, texCoords, faces) }
            }
        }

        // Calculate bounding box
        vertices.forEach { (x, y, z) ->
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
            minZ = minOf(minZ, z)
            maxZ = maxOf(maxZ, z)
        }

        // Convert to arrays for OpenGL
        val vertexArray = mutableListOf<Float>()
        val normalArray = mutableListOf<Float>()
        val texCoordArray = mutableListOf<Float>()
        val indexArray = mutableListOf<Int>()

        var currentIndex = 0
        faces.forEach { face ->
            face.vertices.forEach { vertex ->
                // Add vertex position
                val pos = vertices[vertex.vertexIndex - 1]
                vertexArray.add(pos.first)
                vertexArray.add(pos.second)
                vertexArray.add(pos.third)

                // Add normal
                if (vertex.normalIndex > 0 && vertex.normalIndex <= normals.size) {
                    val normal = normals[vertex.normalIndex - 1]
                    normalArray.add(normal.first)
                    normalArray.add(normal.second)
                    normalArray.add(normal.third)
                } else {
                    normalArray.add(0f)
                    normalArray.add(0f)
                    normalArray.add(1f)
                }

                // Add texture coordinate
                if (vertex.texCoordIndex > 0 && vertex.texCoordIndex <= texCoords.size) {
                    val tex = texCoords[vertex.texCoordIndex - 1]
                    texCoordArray.add(tex.first)
                    texCoordArray.add(tex.second)
                } else {
                    texCoordArray.add(0f)
                    texCoordArray.add(0f)
                }

                indexArray.add(currentIndex++)
            }
        }

        Log.d(TAG, "OBJ loaded: ${vertices.size} vertices, ${faces.size} faces")

        return OBJModel(
            vertices = vertexArray.toFloatArray(),
            normals = normalArray.toFloatArray(),
            texCoords = texCoordArray.toFloatArray(),
            indices = indexArray.toIntArray(),
            bounds = OBJModel.BoundingBox(minX, maxX, minY, maxY, minZ, maxZ)
        )
    }

    private fun parseLine(
        line: String,
        vertices: MutableList<Triple<Float, Float, Float>>,
        normals: MutableList<Triple<Float, Float, Float>>,
        texCoords: MutableList<Pair<Float, Float>>,
        faces: MutableList<Face>
    ) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return

        val parts = trimmed.split("\\s+".toRegex())
        when (parts[0]) {
            "v" -> {
                // Vertex position
                if (parts.size >= 4) {
                    vertices.add(
                        Triple(
                            parts[1].toFloat(),
                            parts[2].toFloat(),
                            parts[3].toFloat()
                        )
                    )
                }
            }
            "vn" -> {
                // Vertex normal
                if (parts.size >= 4) {
                    normals.add(
                        Triple(
                            parts[1].toFloat(),
                            parts[2].toFloat(),
                            parts[3].toFloat()
                        )
                    )
                }
            }
            "vt" -> {
                // Texture coordinate
                if (parts.size >= 3) {
                    texCoords.add(
                        Pair(
                            parts[1].toFloat(),
                            parts[2].toFloat()
                        )
                    )
                }
            }
            "f" -> {
                // Face (triangle or quad)
                if (parts.size >= 4) {
                    val faceVertices = mutableListOf<FaceVertex>()
                    for (i in 1 until parts.size) {
                        faceVertices.add(parseFaceVertex(parts[i]))
                    }

                    // Triangulate if quad
                    if (faceVertices.size == 3) {
                        faces.add(Face(faceVertices))
                    } else if (faceVertices.size == 4) {
                        // Split quad into two triangles
                        faces.add(Face(listOf(faceVertices[0], faceVertices[1], faceVertices[2])))
                        faces.add(Face(listOf(faceVertices[0], faceVertices[2], faceVertices[3])))
                    }
                }
            }
        }
    }

    private fun parseFaceVertex(vertexString: String): FaceVertex {
        val parts = vertexString.split("/")
        return FaceVertex(
            vertexIndex = parts[0].toInt(),
            texCoordIndex = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else 0,
            normalIndex = if (parts.size > 2) parts[2].toInt() else 0
        )
    }

    private data class FaceVertex(
        val vertexIndex: Int,
        val texCoordIndex: Int,
        val normalIndex: Int
    )

    private data class Face(
        val vertices: List<FaceVertex>
    )
}
