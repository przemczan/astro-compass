package com.astrocompass.catalog

import com.astrocompass.astro.Angle
import com.astrocompass.astro.coords.EquatorialCoordinates
import com.astrocompass.astro.io.BinaryReader

/** Decodes the binary blobs [tools/build-catalogs.mjs] produces. Layouts must stay in lockstep
 *  with that script -- see its `BinaryWriter` usage for the authoritative field order. */
object CatalogFormat {

    fun decodeStars(bytes: ByteArray): List<StarObject> {
        val reader = BinaryReader(bytes)
        val count = reader.readInt32()
        return List(count) {
            val hygId = reader.readInt32()
            val hip = reader.readInt32()
            val ra = Angle.ofRadians(reader.readFloat32().toDouble())
            val dec = Angle.ofRadians(reader.readFloat32().toDouble())
            val magnitude = reader.readFloat32()
            val proper = reader.readString()
            val bayer = reader.readString()
            val flamsteed = reader.readUInt8()
            val constellation = reader.readString()
            StarObject(
                hygId = hygId,
                hip = hip,
                properName = proper,
                bayer = bayer,
                flamsteed = flamsteed,
                constellation = constellation,
                j2000 = EquatorialCoordinates(ra, dec),
                magnitude = magnitude,
            )
        }
    }

    fun decodeDeepSkyObjects(bytes: ByteArray): List<DeepSkyObject> {
        val reader = BinaryReader(bytes)
        val count = reader.readInt32()
        return List(count) {
            val name = reader.readString()
            val messier = reader.readUInt8()
            val type = SkyObjectType.fromOrdinalOrOther(reader.readUInt8())
            val ra = Angle.ofRadians(reader.readFloat32().toDouble())
            val dec = Angle.ofRadians(reader.readFloat32().toDouble())
            val magnitude = reader.readFloat32()
            val constellation = reader.readString()
            val commonName = reader.readString()
            val majorAxisArcmin = reader.readFloat32()
            val minorAxisArcmin = reader.readFloat32()
            val positionAngleDegrees = reader.readFloat32()
            DeepSkyObject(
                catalogDesignation = name,
                messier = messier,
                type = type,
                j2000 = EquatorialCoordinates(ra, dec),
                magnitude = magnitude,
                constellation = constellation,
                commonName = commonName,
                majorAxisArcmin = majorAxisArcmin,
                minorAxisArcmin = minorAxisArcmin,
                positionAngleDegrees = positionAngleDegrees,
            )
        }
    }

    fun decodeConstellationLines(bytes: ByteArray): List<ConstellationLine> {
        val reader = BinaryReader(bytes)
        val count = reader.readInt32()
        return List(count) {
            val abbreviation = reader.readString()
            val polylineCount = reader.readInt32()
            val polylines = List(polylineCount) {
                val vertexCount = reader.readInt32()
                List(vertexCount) {
                    val ra = Angle.ofRadians(reader.readFloat32().toDouble())
                    val dec = Angle.ofRadians(reader.readFloat32().toDouble())
                    EquatorialCoordinates(ra, dec)
                }
            }
            ConstellationLine(abbreviation, polylines)
        }
    }

    fun decodeMilkyWayCatalog(bytes: ByteArray): MilkyWayCatalog {
        val reader = BinaryReader(bytes)
        val gridStepDegrees = reader.readFloat32()
        val count = reader.readInt32()
        val cells = List(count) {
            val ra = Angle.ofRadians(reader.readFloat32().toDouble())
            val dec = Angle.ofRadians(reader.readFloat32().toDouble())
            val level = reader.readUInt8()
            MilkyWayCell(EquatorialCoordinates(ra, dec), level)
        }
        return MilkyWayCatalog(gridStepDegrees, cells)
    }
}
