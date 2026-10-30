// SPDX-License-Identifier: GPL-3.0-only
// Official tutorial data are obtained separately; this file contains no data payload.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection

title = 'Tutorial screen family: complete stock traffic map'
description = 'Fixed tutorial scene, one screen at 1, 3 or 5 metres'
inputs = [
        NM_version: [name: 'NM_version', title: 'Engine version', type: String.class, min: 1, max: 1],
        barrier_height: [name: 'barrier_height', title: 'Screen height, metres', type: Integer.class, min: 1, max: 1],
        output_dir: [name: 'output_dir', title: 'Fresh output directory', type: String.class, min: 1, max: 1],
        stock_dir: [name: 'stock_dir', title: 'Official engine distribution', type: String.class, min: 1, max: 1],
        fixture_dir: [name: 'fixture_dir', title: 'Prepared tutorial resources', type: String.class, min: 1, max: 1],
        pins_file: [name: 'pins_file', title: 'Preparation receipt', type: String.class, min: 1, max: 1]
]
outputs = [result: [name: 'result', title: 'Complete native output', type: String.class]]

static int checkedHeight(Object value) {
    String text = String.valueOf(value)
    if (!(text in ['1', '3', '5'])) {
        throw new IllegalArgumentException('Screen height must be exactly 1, 3 or 5 metres')
    }
    return Integer.parseInt(text)
}

static String checkedEngine(Object value, Map pins) {
    String version = String.valueOf(value)
    if (!(version in ['v5.0.0', 'v5.0.1']) || pins.engine_version != version) {
        throw new IllegalArgumentException('A matching pinned v5.0.0 or v5.0.1 receipt is required')
    }
    return version
}

static String footprint() {
    return 'POLYGON((223495.9880411485 6757177.88900822,223515.9880411485 6757177.88900822,223515.9880411485 6757178.08900822,223495.9880411485 6757178.08900822,223495.9880411485 6757177.88900822))'
}

static Map configuration() {
    return [threads: 1, reflection_order: 1, maximum_source_distance_m: 150,
            maximum_reflection_distance_m: 50, diffraction_horizontal: true,
            diffraction_vertical: true, temperature_c: 20, humidity_percent: 70,
            wall_absorption: 0.1]
}

static List requiredStockScripts() {
    return ['noisemodelling/wps/Import_and_Export/Import_File.groovy',
            'noisemodelling/wps/NoiseModelling/Noise_level_from_traffic.groovy',
            'noisemodelling/wps/Import_and_Export/Export_Table.groovy']
}

static String sha256(byte[] bytes) {
    return MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
}

static Script loadTrackedScript(GroovyShell shell, File stock, String relative, Map pins) {
    if (!(relative in requiredStockScripts()) || !(pins[relative] instanceof String)) {
        throw new IllegalArgumentException('Missing declared stock script pin: ' + relative)
    }
    File path = new File(stock, relative)
    if (!path.isFile()) throw new FileNotFoundException('Missing stock script: ' + relative)
    byte[] bytes = path.bytes
    if (sha256(bytes) != pins[relative]) {
        throw new IllegalStateException('Stock script hash mismatch: ' + relative)
    }
    // Parse precisely the bytes just hashed, rather than reopening a mutable file.
    return shell.parse(new String(bytes, StandardCharsets.UTF_8), path.name)
}

static def exec(Connection connection, Map input) {
    long started = System.nanoTime()
    int height = checkedHeight(input.barrier_height)
    File output = new File(input.output_dir as String)
    File stock = new File(input.stock_dir as String)
    File resources = new File(input.fixture_dir as String)
    File pinFile = new File(input.pins_file as String)
    Map pins = new JsonSlurper().parse(pinFile) as Map
    checkedEngine(input.NM_version, pins)
    if (pins.schema != 1 || pins['case'] != 'tutorial-barrier' ||
            pins.configuration != configuration() || pins.screen_wkt != footprint() ||
            pins.screen_id != 1702 || pins.srid != 2154) {
        throw new IllegalArgumentException('Preparation receipt does not match this tutorial recipe')
    }
    if (!output.isDirectory() && !output.mkdirs()) throw new IOException('Cannot create output directory')
    if (new File(output, 'IDENTITY.json').exists() || new File(output, 'RECEIVERS_LEVEL.csv').exists()) {
        throw new IllegalStateException('Case output exists; use a fresh directory/workspace')
    }
    Map resourcePins = pins.source_resource_sha256 as Map
    if (!resourcePins || resourcePins.size() != 17) throw new IllegalArgumentException('Missing complete tutorial input pins')
    resourcePins.each { name, expected ->
        File path = new File(resources, name as String)
        if (!path.isFile() || sha256(path.bytes) != expected) {
            throw new IllegalStateException('Tutorial resource hash mismatch: ' + name)
        }
    }
    Map scriptPins = pins.stock_script_sha256 as Map
    if (scriptPins.keySet() != requiredStockScripts().toSet()) {
        throw new IllegalArgumentException('Exactly three loaded stock scripts must be pinned')
    }
    GroovyShell shell = new GroovyShell()
    Script importer = loadTrackedScript(shell, stock, requiredStockScripts()[0], scriptPins)
    Script noise = loadTrackedScript(shell, stock, requiredStockScripts()[1], scriptPins)
    Script exporter = loadTrackedScript(shell, stock, requiredStockScripts()[2], scriptPins)
    ['ground_type.shp', 'buildings.shp', 'receivers.shp', 'ROADS2.shp', 'dem.geojson'].each { name ->
        importer.invokeMethod('exec', [connection, [pathFile: new File(resources, name).absolutePath]])
    }
    Sql sql = new Sql(connection)
    if (sql.firstRow('SELECT COUNT(*) AS N FROM BUILDINGS').N != 1701 ||
            sql.firstRow('SELECT MAX(PK) AS N FROM BUILDINGS').N != 1701 ||
            sql.firstRow('SELECT COUNT(*) AS N FROM RECEIVERS').N != 830) {
        throw new IllegalStateException('Fresh declared tutorial database required')
    }
    sql.execute('INSERT INTO BUILDINGS(PK, THE_GEOM, HEIGHT, ID_WAY) VALUES (?, ST_MULTI(ST_GEOMFROMTEXT(?, ?)), ?, ?)',
            [1702, footprint(), 2154, height, -1000001])
    Map counts = [:]
    ['BUILDINGS', 'ROADS2', 'RECEIVERS', 'DEM', 'GROUND_TYPE'].each { table ->
        counts[table] = sql.firstRow('SELECT COUNT(*) AS N FROM ' + table).N
    }
    counts.receivers = counts.RECEIVERS
    long prepared = System.nanoTime()
    noise.invokeMethod('exec', [connection, [
            tableBuilding: 'BUILDINGS', tableRoads: 'ROADS2', tableReceivers: 'RECEIVERS',
            tableDEM: 'DEM', tableGroundAbs: 'GROUND_TYPE',
            confReflOrder: '1', confMaxSrcDist: '150', confMaxReflDist: '50',
            confThreadNumber: '1', confDiffVertical: true, confDiffHorizontal: true,
            confMaxError: '0', confExportSourceId: false,
            confTemperature: 20.0, confHumidity: 70.0, paramWallAlpha: '0.1']])
    long computed = System.nanoTime()
    int rowCount = sql.firstRow('SELECT COUNT(*) AS N FROM RECEIVERS_LEVEL').N as int
    if (rowCount < 1) throw new IllegalStateException('Native receiver output is empty')
    def statement = connection.createStatement()
    List columns
    try {
        def resultSet = statement.executeQuery('SELECT * FROM RECEIVERS_LEVEL LIMIT 0')
        def metadata = resultSet.metaData
        columns = (1..metadata.columnCount).collect { metadata.getColumnLabel(it) }
        resultSet.close()
    } finally {
        statement.close()
    }
    ['csv', 'geojson'].each { extension ->
        exporter.invokeMethod('exec', [connection, [tableToExport: 'RECEIVERS_LEVEL',
                exportPath: new File(output, 'RECEIVERS_LEVEL.' + extension).absolutePath]])
    }
    long exported = System.nanoTime()
    Map identity = [schema: 1, 'case': 'tutorial-barrier', height_m: height,
            engine_version: input.NM_version, source_resource_sha256: resourcePins,
            stock_script_sha256: scriptPins, configuration: configuration(),
            screen_wkt: footprint(), screen_id: 1702, srid: 2154,
            counts: counts, row_count: rowCount, columns: columns, complete_native_export: true]
    new File(output, 'IDENTITY.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(identity)) + '\n'
    Map statistics = [version: input.NM_version, 'case': 'tutorial-barrier', height_m: height,
            receivers: counts.receivers, exported_rows: rowCount,
            preparation_seconds: (prepared - started) / 1e9,
            calculation_seconds: (computed - prepared) / 1e9,
            export_seconds: (exported - computed) / 1e9,
            script_seconds: (exported - started) / 1e9,
            note: 'Script wall excludes JVM startup/shutdown; full exports retained, verification outside calculation']
    new File(output, 'stats_' + input.NM_version + '.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(statistics)) + '\n'
    return 'Complete native tutorial screen case exported'
}
