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
import org.noise_planet.noisemodelling.wps.Acoustic_Tools.Create_Isosurface
import org.noise_planet.noisemodelling.wps.Geometric_Tools.Change_SRID
import org.noise_planet.noisemodelling.wps.NoiseModelling.Noise_level_from_source
import org.noise_planet.noisemodelling.wps.NoiseModelling.Road_Emission_from_Traffic
import org.noise_planet.noisemodelling.wps.Receivers.Delaunay_Grid
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.noise_planet.noisemodelling.wps.Import_and_Export.Export_Table
import org.noise_planet.noisemodelling.wps.Import_and_Export.Import_Asc_File
import org.noise_planet.noisemodelling.wps.Import_and_Export.Import_File
import org.h2gis.utilities.JDBCUtilities

import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.lang.management.ThreadMXBean
import java.sql.Connection
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

import java.util.concurrent.atomic.AtomicBoolean

title = 'NoiseModelling benchmark simulation'
description = 'NoiseModelling benchmark simulation'

inputs = [
        NM_version:[
                title      : 'Version of NoiseModelling',
                name       : 'Version of NoiseModelling',
                description: 'Version of NoiseModellingd.',
                min        : 0, max: 1,
                type       : String.class,
        ]]

outputs = [result: [name: 'Result output string', title: 'Result output string', description: 'This type of result does not allow the blocks to be linked together.', type: String.class]]

private static String threadDump(boolean lockedMonitors, boolean lockedSynchronizers) {
    StringBuffer threadDump = new StringBuffer(System.lineSeparator());
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    for(ThreadInfo threadInfo : threadMXBean.dumpAllThreads(lockedMonitors, lockedSynchronizers)) {
        threadDump.append(threadInfo.toString());
    }
    return threadDump.toString();
}

static def exec(Connection connection, Map input) {
    String version=""
    if(input.containsKey('NM_version')){
        version=input["NM_version"] as String
    }
    def outputFolder = new File("output/$version")
    if (!outputFolder.exists()) {
        outputFolder.mkdir()
    }
    def redoDelaunayGrid = false
    def redoRoadsEmission = false
    def redoCompute = true
    def sql = new Sql(connection)

    if (version.startsWith("v4")){
            long maxUsedMemory = 0
            if (!JDBCUtilities.tableExists(connection, "BUILDINGS")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/BUILDINGS.geojson",
                         "inputSRID": "2154",
                         "tableName": "BUILDINGS"])
            }

            if (!JDBCUtilities.tableExists(connection, "DEM")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/DEM.geojson",
                         "inputSRID": 2154,
                         "tableName": "DEM"])
            }

            if (!JDBCUtilities.tableExists(connection, "GROUNDS")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/GROUNDS.geojson",
                         "inputSRID": "2154",
                         "tableName": "GROUNDS"])
            }

            if (!JDBCUtilities.tableExists(connection, "LW_ROADS")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/LW_ROADS_LW.geojson",
                         "inputSRID": 2154,
                         "tableName": "LW_ROADS_LW"])

            }

            if (!JDBCUtilities.tableExists(connection, "RECEIVERS")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/RECEIVERS.geojson",
                         "inputSRID": "2154",
                         "tableName": "RECEIVERS"])
            }

            if (!JDBCUtilities.tableExists(connection, "TRIANGLES")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/TRIANGLES.geojson",
                         "inputSRID": "2154",
                         "tableName": "TRIANGLES"])
            }

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

            }

            if (redoRoadsEmission) {
                if (!JDBCUtilities.tableExists(connection, "ROADS")) {
                    new Import_File().exec(connection,
                            ["pathFile" : "input/clisson/clisson/ROADS.geojson",
                             "inputSRID": "2154",
                             "tableName": "ROADS"])
                }

                new Road_Emission_from_Traffic().exec(connection,
                        ["tableRoads": "ROADS"])

            }

            long elapsed = 0

            if(redoCompute) {
                def running = new AtomicBoolean(true)

                def monitor = Thread.start {
                    def rt = Runtime.getRuntime()
                    def avant = rt.freeMemory()

                    while (running.get()) {
                        long used = rt.freeMemory()
                        long diff = Math.abs(avant - used) / (1024 * 1024 * 1024)


                        if (diff > maxUsedMemory) {
                            maxUsedMemory = diff
                        }

                        sleep(1000)
                    }
                }

                long startCompute = System.currentTimeMillis()

                new Noise_level_from_source().exec(connection,
                        ["tableBuilding"                   : "BUILDINGS",
                         "tableSources"                    : "LW_ROADS_LW",
                         "tableReceivers"                  : "RECEIVERS",
                         "tableDEM"                        : "DEM",
                         "tableGroundAbs"                  : "GROUNDS",
                         "confReflOrder"                   : 1,
                         "confMaxSrcDist"                  : 300,
                         "confDiffHorizontal"              : true,
                         "confMaxError"                    : 0.1,
                         "confFavorableOccurrencesDay"     : '0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25',
                         "confRaysName":"RAYS"
                        ])

                elapsed = System.currentTimeMillis() - startCompute
                running.set(false)
                monitor.join()
            }


            new Export_Table().exec(connection,
                    ["exportPath"   : "$outputFolder/RECEIVERS_LEVEL.geojson",
                     "tableToExport": "LDAY_GEOM"])

            def rowRays
            double timeRays
            if((version =="v4.0.2")  || (version=="v4.0.4") || (version=="v4.0.5")){
                def rays = sql.firstRow("SELECT COUNT(IDSOURCE) nbRays FROM RAYS")
                def rays_int = rays.nbRays
                rowRays = rays_int
                timeRays = elapsed/rowRays
            }


            new Create_Isosurface().exec(connection,
                    ["resultTable": "LDAY_GEOM",
                     "keepTriangles": true,
                     "smoothCoefficient" : 0])

            sql.execute("DROP TABLE IF EXISTS KEPLERGL")

            sql.execute("CREATE TABLE KEPLERGL AS SELECT ST_Transform(THE_GEOM, 4326) THE_GEOM, ISOLABEL FROM CONTOURING_NOISE_MAP")

            new Export_Table().exec(connection,
                    ["exportPath"   : "$outputFolder/KEPLERGL.geojson",
                     "tableToExport": "KEPLERGL"])

            threadDump(true, true)


            def cpt = sql.firstRow("SELECT COUNT(*) FROM RECEIVERS")[0] as Integer

            def time = elapsed/cpt

            long hours = TimeUnit.MILLISECONDS.toHours(elapsed)
            elapsed -= TimeUnit.HOURS.toMillis(hours)
            long minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
            elapsed -= TimeUnit.MINUTES.toMillis(minutes)
            long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed)
            String timeString = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)

            println("Compuation of $cpt receivers in $timeString ( ${elapsed/cpt} milliseconds per receiver")

            def geojsonFile = new File("$outputFolder/RECEIVERS_LEVEL.geojson")

            def json = new JsonSlurper().parse(geojsonFile)

            def values = []

            json.features.each { f ->
                def val = f.properties?.LAEQ
                if (val != null) {
                    values.add( Double.valueOf(val as double))
                }
            }

            def mean = values.sum() / values.size()




            def bins = ["<35", "35-40", "40-45", "45-50", "50-55", "55-60", "60-65", "65-70", "70-75", ">75"]
            def histogram = bins.collectEntries { [it, 0] }

            values.each { v ->
                if      (v < 35)  histogram["<35"]++
                else if (v < 40)  histogram["35-40"]++
                else if (v < 45)  histogram["40-45"]++
                else if (v < 50)  histogram["45-50"]++
                else if (v < 55)  histogram["50-55"]++
                else if (v < 60)  histogram["55-60"]++
                else if (v < 65)  histogram["60-65"]++
                else if (v < 70)  histogram["65-70"]++
                else if (v < 75)  histogram["70-75"]++
                else              histogram[">75"]++
            }

            DecimalFormat f = new DecimalFormat()
            f.setMaximumFractionDigits(2)

            def result = [
                    mean: mean,
                    memory: maxUsedMemory,
                    time: timeString,
                    timePerReceive: f.format(time),
                    nbRays: rowRays,
                    timePerRays: f.format(timeRays),
                    nb_receiver: cpt,
                    confMaxError: 0.1,
                    histogram: histogram
            ]
                def outFile = new File("$outputFolder/stats_${version}.json")
                outFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(result))

    }
    else{
            long maxUsedMemory = 0
            if (!JDBCUtilities.tableExists(connection, "BUILDINGS")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/BUILDINGS.geojson",
                         "inputSRID": "2154",
                         "tableName": "BUILDINGS"])
            }

            if (!JDBCUtilities.tableExists(connection, "DEM")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/DEM.geojson",
                         "inputSRID": 2154,
                         "tableName": "DEM"])
            }

            if (!JDBCUtilities.tableExists(connection, "GROUNDS")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/GROUNDS.geojson",
                         "inputSRID": "2154",
                         "tableName": "GROUNDS"])
            }

            if (!JDBCUtilities.tableExists(connection, "LW_ROADS")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/LW_ROADS.geojson",
                         "inputSRID": "2154",
                         "tableName": "LW_ROADS"])
            }

            if (!JDBCUtilities.tableExists(connection, "RECEIVERS")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/RECEIVERS.geojson",
                         "inputSRID": "2154",
                         "tableName": "RECEIVERS"])
            }

            if (!JDBCUtilities.tableExists(connection, "TRIANGLES")) {
                new Import_File().exec(connection,
                        ["pathFile" : "input/clisson/clisson/TRIANGLES.geojson",
                         "inputSRID": "2154",
                         "tableName": "TRIANGLES"])
            }



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
                        ["exportPath"   : "input/clisson/RECEIVERS.geojson",
                         "tableToExport": "RECEIVERS"])

                new Export_Table().exec(connection,
                        ["exportPath"   : "input/clisson/TRIANGLES.geojson",
                         "tableToExport": "TRIANGLES"])
            }

            if (redoRoadsEmission) {
                if (!JDBCUtilities.tableExists(connection, "ROADS")) {
                    new Import_File().exec(connection,
                            ["pathFile" : "input/clisson/clisson/ROADS.geojson",
                             "inputSRID": "2154",
                             "tableName": "ROADS"])
                }

                new Road_Emission_from_Traffic().exec(connection,
                        ["tableRoads": "ROADS"])

                sql.execute("DELETE FROM LW_ROADS WHERE THE_GEOM IS NULL")

                /*new Export_Table().exec(connection,
                        ["exportPath"   : "input/clisson/LW_ROADS.shp",
                         "tableToExport": "LW_ROADS"])*/

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

                /*new Export_Table().exec(connection,
                        ["exportPath"   : "input/clisson/LW_ROADS_LW.shp",
                         "tableToExport": "LW_ROADS_LW"])*/
            }

            long elapsed = 0

            if(redoCompute) {
                def running = new AtomicBoolean(true)


                def monitor = Thread.start {
                    def rt = Runtime.getRuntime()
                    def avant = rt.freeMemory()

                    while (running.get()) {
                        long used = rt.freeMemory()
                        long diff = Math.abs(avant - used) / (1024 * 1024 * 1024)

                        if (diff > maxUsedMemory) {
                            maxUsedMemory = diff
                        }

                        sleep(1000)
                    }
                }

                long startCompute = System.currentTimeMillis()

                new Noise_level_from_source().exec(connection,
                        ["tableBuilding"                   : "BUILDINGS",
                         "tableSources"                    : "LW_ROADS",
                         "tableReceivers"                  : "RECEIVERS",
                         "tableDEM"                        : "DEM",
                         "tableGroundAbs"                  : "GROUNDS",
                         "confReflOrder"                   : 1,
                         "confMaxSrcDist"                  : 300,
                         "confRaysName"                    : "RAYS",
                         "confDiffHorizontal"              : true,
                         "confMaxError"                    : 0.1,
                         "confFavourableOccurrencesDefault": '0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25'
                        ])

                elapsed = System.currentTimeMillis() - startCompute
                running.set(false)
                monitor.join()

            }



            new Export_Table().exec(connection,
                    ["exportPath"   : "$outputFolder/RECEIVERS_LEVEL.geojson",
                     "tableToExport": "RECEIVERS_LEVEL"])





            def rowRAYS = sql.firstRow("SELECT COUNT(IDSOURCE) nbRays FROM RAYS")

            new Create_Isosurface().exec(connection,
                    ["resultTable": "RECEIVERS_LEVEL",
                     "keepTriangles": true,
                     "smoothCoefficient" : 0])

            sql.execute("DROP TABLE IF EXISTS KEPLERGL")

            sql.execute("CREATE TABLE KEPLERGL AS SELECT ST_Transform(THE_GEOM, 4326) THE_GEOM, ISOLABEL FROM CONTOURING_NOISE_MAP WHERE PERIOD='DEN'")

            new Export_Table().exec(connection,
                    ["exportPath"   : "$outputFolder/KEPLERGL.geojson",
                     "tableToExport": "KEPLERGL"])

            threadDump(true, true)


            def cpt = sql.firstRow("SELECT COUNT(*) FROM RECEIVERS")[0] as Integer
            double time = elapsed/cpt
            def elapsedRay = elapsed

            long hours = TimeUnit.MILLISECONDS.toHours(elapsed)
            elapsed -= TimeUnit.HOURS.toMillis(hours)
            long minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
            elapsed -= TimeUnit.MINUTES.toMillis(minutes)
            long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed)
            String timeString = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)

            println("Compuation of $cpt receivers in $timeString ( ${elapsed/cpt} milliseconds per receiver")

            def geojsonFile = new File("$outputFolder/RECEIVERS_LEVEL.geojson")

            def json = new JsonSlurper().parse(geojsonFile)

            def values = []

            json.features.each { f ->
                def val = f.properties?.LAEQ
                def period = f.properties?.PERIOD
                if (val != null && period=="D") {
                    values.add( Double.valueOf(val as double))
                }
            }

            def mean = values.sum() / values.size()


            def bins = ["<35", "35-40", "40-45", "45-50", "50-55", "55-60", "60-65", "65-70", "70-75", ">75"]
            def histogram = bins.collectEntries { [it, 0] }

            values.each { v ->
                if      (v < 35)  histogram["<35"]++
                else if (v < 40)  histogram["35-40"]++
                else if (v < 45)  histogram["40-45"]++
                else if (v < 50)  histogram["45-50"]++
                else if (v < 55)  histogram["50-55"]++
                else if (v < 60)  histogram["55-60"]++
                else if (v < 65)  histogram["60-65"]++
                else if (v < 70)  histogram["65-70"]++
                else if (v < 75)  histogram["70-75"]++
                else              histogram[">75"]++
            }
            double timeRay = elapsedRay/rowRAYS.nbRays
            DecimalFormat f = new DecimalFormat()
            f.setMaximumFractionDigits(2)


            def result = [
                    mean: mean,
                    memory: maxUsedMemory,
                    time: timeString,
                    timePerReceive: f.format(time),
                    nbRays: rowRAYS.nbRays,
                    timePerRays:f.format(timeRay),
                    nb_receiver: cpt,
                    confMaxError: 0.1,
                    histogram: histogram
            ]

            def outFile = new File("$outputFolder/stats_${version}.json")
            outFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(result))


        }

        System.exit(0)
}
