/**
 * NoiseModelling is an open-source tool designed to produce environmental noise maps
 * on very large urban areas. It can be used as a Java library or be controlled through
 * a user friendly web interface.
 *
 * This version is developed by the DECIDE team from the Lab-STICC (CNRS) and by the
 * Mixt Research Unit in Environmental Acoustics (Université Gustave Eiffel).
 * <http://noise-planet.org/noisemodelling.html>
 *
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this
 * License in the file LICENCE provided with this software.
 *
 * Contact: contact@noise-planet.org
 */

/**
 * @Author Pierre Aumond, Université Gustave Eiffel
 * @Author Nicolas Fortin, Université Gustave Eiffel
 */


import groovy.sql.Sql
import org.h2gis.api.ProgressVisitor
import org.noise_planet.noisemodelling.wps.NoiseModelling.Noise_level_from_source
import org.noise_planet.noisemodelling.wps.NoiseModelling.Road_Emission_from_Traffic
import org.noise_planet.noisemodelling.wps.Receivers.Delaunay_Grid
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.noise_planet.noisemodelling.wps.Import_and_Export.Export_Table
import org.noise_planet.noisemodelling.wps.Import_and_Export.Import_Asc_File
import org.noise_planet.noisemodelling.wps.Import_and_Export.Import_File
import org.h2gis.utilities.JDBCUtilities
import java.sql.Connection

title = 'NoiseModelling benchmark simulation'
description = 'NoiseModelling benchmark simulation'

inputs = [:]

outputs = [result: [name: 'Result output string', title: 'Result output string', description: 'This type of result does not allow the blocks to be linked together.', type: String.class]]

static def exec(Connection connection, input) {

    def redoDelaunayGrid = false
    def redoRoadsEmission = false

    if (!JDBCUtilities.tableExists(connection, "BUILDINGS")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/clisson/BUILDINGS.shp",
                 "inputSRID": "2154",
                 "tableName": "BUILDINGS"])
    }

    if (!JDBCUtilities.tableExists(connection, "DEM")) {
        new Import_Asc_File().exec(connection,
                ["pathFile" : "input/clisson/dem_5m.asc.gz",
                 "inputSRID": 2154])
    }

    if (!JDBCUtilities.tableExists(connection, "GROUNDS")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/clisson/GROUNDS.shp",
                 "inputSRID": "2154",
                 "tableName": "GROUNDS"])
    }

    if (!JDBCUtilities.tableExists(connection, "LW_ROADS")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/clisson/LW_ROADS.shp",
                 "inputSRID": "2154",
                 "tableName": "ROADS"])
    }

    if (!JDBCUtilities.tableExists(connection, "RECEIVERS")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/clisson/RECEIVERS.shp",
                 "inputSRID": "2154",
                 "tableName": "RECEIVERS"])
    }

    if (!JDBCUtilities.tableExists(connection, "TRIANGLES")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/clisson/TRIANGLES.shp",
                 "inputSRID": "2154",
                 "tableName": "TRIANGLES"])
    }

    def sql = new Sql(connection)

    if (redoDelaunayGrid) {
        sql.execute("DROP TABLE RECEIVERS IF EXISTS")

        if (!JDBCUtilities.tableExists(connection, "RECEIVERS")) {
            new Delaunay_Grid().exec(connection,
                    ["tableBuilding"      : "BUILDINGS",
                     "maxArea"            : 2500,
                     "sourcesTableName"   : "ROADS",
                     "fenceNegativeBuffer": 3500,
                     "maxCellDist"        : 2000])
        }

        new Export_Table().exec(connection,
                ["exportPath"   : "input/clisson/RECEIVERS.shp",
                 "tableToExport": "RECEIVERS"])

        new Export_Table().exec(connection,
                ["exportPath"   : "input/clisson/TRIANGLES.shp",
                 "tableToExport": "TRIANGLES"])
    }

    if (redoRoadsEmission) {
        if (!JDBCUtilities.tableExists(connection, "ROADS")) {
            new Import_File().exec(connection,
                    ["pathFile" : "input/clisson/ROADS.shp",
                     "inputSRID": "2154",
                     "tableName": "ROADS"])
        }

        new Road_Emission_from_Traffic().exec(connection,
                ["tableRoads": "ROADS"])

        sql.execute("DELETE FROM LW_ROADS WHERE THE_GEOM IS NULL")

        new Export_Table().exec(connection,
                ["exportPath"   : "input/clisson/LW_ROADS.shp",
                 "tableToExport": "LW_ROADS"])

        sql.execute("DROP TABLE IF EXISTS LW_ROADS_LW")

        sql.execute("CREATE TABLE LW_ROADS_LW AS SELECT * FROM LW_ROADS")

        def fields = ["HZD63", "HZD125", "HZD250", "HZD500", "HZD1000", "HZD2000", "HZD4000", "HZD8000", "HZE63",
                      "HZE125", "HZE250", "HZE500", "HZE1000", "HZE2000", "HZE4000", "HZE8000", "HZN63", "HZN125",
                      "HZN250", "HZN500", "HZN1000", "HZN2000", "HZN4000", "HZN8000"]
        fields.forEach {
            field ->
                def fieldLw = field.replace("HZ", "LW")
                sql.execute("ALTER TABLE LW_ROADS_LW RENAME COLUMN $field TO $fieldLw" as String)
        }

        new Export_Table().exec(connection,
                ["exportPath"   : "input/clisson/LW_ROADS_LW.shp",
                 "tableToExport": "LW_ROADS_LW"])
    }

    new Noise_level_from_source().exec(connection,
            ["tableBuilding"     : "BUILDINGS",
             "tableSources"      : "LW_ROADS",
             "tableReceivers"    : "RECEIVERS",
             "tableDEM"          : "DEM",
             "tableGroundAbs"    : "GROUNDS",
             "confReflOrder"     : 1,
             "confMaxSrcDist"    : 300,
             "confDiffHorizontal": true,
             "confMaxError"      : 0
            ])

    def outputFolder = new File("output/v5.0")
    if(!outputFolder.exists()){
        outputFolder.mkdir()
    }

    new Export_Table().exec(connection,
            ["exportPath"   : "$outputFolder/RECEIVERS_LEVEL.shp",
             "tableToExport": "RECEIVERS_LEVEL"])
}
