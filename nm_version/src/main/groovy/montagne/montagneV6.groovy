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
import org.noise_planet.noisemodelling.scripts.Acoustic_Tools.Create_Isosurface
import org.noise_planet.noisemodelling.scripts.NoiseModelling.Road_Emission_from_Traffic
import org.noise_planet.noisemodelling.scripts.NoiseModelling.Noise_level_from_source
import org.noise_planet.noisemodelling.scripts.Receivers.Delaunay_Grid
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.noise_planet.noisemodelling.scripts.Import_and_Export.Export_Table
import org.noise_planet.noisemodelling.scripts.Geometric_Tools.Set_Height
import org.noise_planet.noisemodelling.scripts.Import_and_Export.Import_File
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
    def outputFolder = new File("output/montagne/$version")
    if (!outputFolder.exists()) {
        outputFolder.mkdir()
    }
    def redoDelaunayGrid = false
    def redoRoadsEmission = false
    def redoCompute = true
    def sql = new Sql(connection)

    if (!JDBCUtilities.tableExists(connection, "BUILDINGS")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/montagne/BUILDINGS.geojson",
                 "inputSRID": "2154",
                 "tableName": "BUILDINGS"])
    }

    if (!JDBCUtilities.tableExists(connection, "DEM")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/montagne/DEM.geojson",
                 "inputSRID": 2154,
                 "tableName": "DEM"])
    }

    if (!JDBCUtilities.tableExists(connection, "GROUNDS")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/montagne/GROUNDS.geojson",
                 "inputSRID": "2154",
                 "tableName": "GROUNDS"])
    }

    if (!JDBCUtilities.tableExists(connection, "LW_ROADS")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/montagne/LW_ROADS.geojson",
                 "inputSRID": 2154,
                 "tableName": "LW_ROADS"])

    }

    if (!JDBCUtilities.tableExists(connection, "RECEIVERS")) {
        new Import_File().exec(connection,
                ["pathFile" : "input/montagne/RECEIVERS.geojson",
                 "inputSRID": "2154",
                 "tableName": "RECEIVERS"])
    }

    long elapsed = 0

    if(redoCompute) {

        long startCompute = System.currentTimeMillis()
        /*if(version == "v6.0.0"){

            def scriptFile = new File("nm_version/src/main/groovy/v600Noise_level_from_source.groovy")
                    .getAbsoluteFile()

            if (!scriptFile.exists()) {
                throw new FileNotFoundException("Fichier introuvable : ${scriptFile.absolutePath}")
            }

            def customClass = new GroovyClassLoader().parseClass(scriptFile)
            def customScript = customClass.newInstance()
            customScript.exec(connection, [
                    "tableBuilding" : "BUILDINGS",
                    "tableSources"      : "LW_ROADS",
                    "tableReceivers"    : "RECEIVERS",
                    "tableDEM"          : "DEM",
                    "tableGroundAbs"    : "GROUNDS",
                    "confReflOrder"     : 1,
                    "confMaxSrcDist"    : 300,
                    "confDiffHorizontal": true,
                    "confMaxError": 0.1,
                    "confFavorableOccurrencesDefault":'0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25'
            ])
        }
        else{*/
            new Set_Height().exec(connection,
                    ["tableName": "LW_ROADS",
                     "height": 0.05])
            new Set_Height().exec(connection,
                    ["tableName": "RECEIVERS",
                     "height": 1.2])

            new Noise_level_from_source().exec(connection,
                    ["tableBuilding"     : "BUILDINGS",
                     "tableSources"      : "LW_ROADS",
                     "tableReceivers"    : "RECEIVERS",
                     "tableDEM"          : "DEM",
                     "tableGroundAbs"    : "GROUNDS",
                     "confRaysName"      : "RAYS",
                     "confReflOrder"     : 1,
                     "confMaxSrcDist"    : 1000,
                     "confDiffHorizontal": true,
                     "confMaxError": 0.1,
                     "confFavorableOccurrencesDefault":'0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25'
                    ])
       // }

        elapsed = System.currentTimeMillis() - startCompute
        sql.execute("DROP TABLE IF EXISTS RECEIVERS_LEVEL_D")

        sql.execute("CREATE TABLE RECEIVERS_LEVEL_D AS SELECT * FROM RECEIVERS_LEVEL WHERE PERIOD='D'")


        new Export_Table().exec(connection,
                ["exportPath"   : "$outputFolder/RECEIVERS_LEVEL.geojson",
                 "tableToExport": "RECEIVERS_LEVEL_D"])

    }

    /*new Create_Isosurface().exec(connection,
            ["resultTable": "RECEIVERS_LEVEL",
             "keepTriangles": false,
             "smoothCoefficient" : 0])

    sql.execute("DROP TABLE IF EXISTS KEPLERGL")

    sql.execute("CREATE TABLE KEPLERGL AS SELECT ST_Transform(THE_GEOM, 4326) THE_GEOM, ISOLABEL FROM CONTOURING_NOISE_MAP WHERE PERIOD='DEN'")

    new Export_Table().exec(connection,
            ["exportPath"   : "$outputFolder/ISO_CONTOUR.geojson",
             "tableToExport": "KEPLERGL"])*/

    threadDump(true, true)


    def cpt = sql.firstRow("SELECT COUNT(*) FROM RECEIVERS")[0] as Integer
    def nbRays =  sql.firstRow("SELECT COUNT(*) FROM RAYS")[0] as Integer
    double time = elapsed/cpt
    def timeray = elapsed/nbRays

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
        def props = f.get("properties")
        def val = props.get("LAEQ")
        def period = props.get("PERIOD")
        if (val != null && period=="D") {
            values.add(Double.valueOf(val as double))
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
            time: timeString,
            timePerReceive: f.format(time),
            nbRays : nbRays,
            timePerRays: timeray,
            histogram: histogram
    ]

    def outFile = new File("$outputFolder/stats_${version}.json")
    outFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(result))

    println("fini***************************************************************************")

    System.exit(0)
}
