// SPDX-License-Identifier: GPL-3.0-only
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.After
import org.junit.Before
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

import static org.junit.Assert.*

/** Small fixture checks only: no propagation calculation or third-party data. */
class TutorialBarrierFamilyTest {
    File temporary

    @Before
    void createTemporaryDirectory() {
        temporary = Files.createTempDirectory('nm-tutorial-fixture-').toFile()
    }

    @After
    void removeTemporaryDirectory() {
        temporary.deleteDir()
    }

    private static String hash(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }

    private static void expectFailure(Closure action) {
        try {
            action.call()
            fail('An invalid fixture or stock pin must be rejected at runtime')
        } catch (Exception expected) {
            assertNotNull(expected.message)
        }
    }

    @Test
    void exposesCompleteInputAndOutputMetadataToTheStockCli() {
        Script script = new runscriptTutorialV5()
        // run() only declares the WPS metadata. Never invoke exec/propagation here.
        script.run()
        Map inputs = script.binding.getVariable('inputs') as Map
        assertEquals(['NM_version', 'barrier_height', 'output_dir', 'stock_dir',
                      'fixture_dir', 'pins_file'].toSet(), inputs.keySet())
        inputs.each { key, attributes ->
            // Stock Main builds each CLI Option using descriptor.name/type.
            assertTrue(attributes.name instanceof String && !attributes.name.trim().isEmpty())
            assertTrue(attributes.title instanceof String && !attributes.title.trim().isEmpty())
            assertEquals(key == 'barrier_height' ? Integer.class : String.class, attributes.type)
            assertTrue(attributes.min instanceof Integer && attributes.max instanceof Integer)
            assertEquals(1, attributes.min.intValue())
            assertEquals(1, attributes.max.intValue())
        }
        Map outputs = script.binding.getVariable('outputs') as Map
        assertEquals(['result'].toSet(), outputs.keySet())
        assertTrue(outputs.result.name instanceof String && !outputs.result.name.trim().isEmpty())
        assertTrue(outputs.result.title instanceof String && !outputs.result.title.trim().isEmpty())
        assertEquals(String.class, outputs.result.type)
    }

    @Test
    void acceptsOnlyTheThreeDeclaredHeightsWithoutTruncation() {
        [1, 3, 5].each { height ->
            assertEquals(height, runscriptTutorialV5.checkedHeight(height))
            assertEquals(height, runscriptTutorialV5.checkedHeight(height.toString()))
        }
        [null, 0, 2, 4, 6, -1, 3.0d, '3.0', ' 3', '3 ', 'three', true].each { invalid ->
            expectFailure { runscriptTutorialV5.checkedHeight(invalid) }
        }
    }

    @Test
    void acceptsBothDeclaredEnginesOnlyWithMatchingPreparationPins() {
        ['v5.0.0', 'v5.0.1'].each { version ->
            assertEquals(version, runscriptTutorialV5.checkedEngine(version, [engine_version: version]))
        }
        List invalid = [[value: 'v5.0.0', pins: [engine_version: 'v5.0.1']],
                        [value: 'v5.0.1', pins: [engine_version: 'v5.0.0']],
                        [value: 'v6.0.0', pins: [engine_version: 'v6.0.0']],
                        [value: 'v4.0.0', pins: [engine_version: 'v4.0.0']],
                        [value: '5.0.1', pins: [engine_version: '5.0.1']],
                        [value: null, pins: [engine_version: null]],
                        [value: 'v5.0.1', pins: [:]]]
        invalid.each { example ->
            try {
                runscriptTutorialV5.checkedEngine(example.value, example.pins)
                fail('Unsupported or mismatched engine identity must be rejected')
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.message)
            }
        }
    }

    @Test
    void heightSelectionKeepsTheDeclaredFootprintAndConfiguration() {
        String expectedFootprint = 'POLYGON((223495.9880411485 6757177.88900822,223515.9880411485 6757177.88900822,223515.9880411485 6757178.08900822,223495.9880411485 6757178.08900822,223495.9880411485 6757177.88900822))'
        Map expectedConfiguration = [threads: 1, reflection_order: 1,
                maximum_source_distance_m: 150, maximum_reflection_distance_m: 50,
                diffraction_horizontal: true, diffraction_vertical: true,
                temperature_c: 20, humidity_percent: 70, wall_absorption: 0.1]
        [1, 3, 5].each { height ->
            runscriptTutorialV5.checkedHeight(height)
            assertEquals(expectedFootprint, runscriptTutorialV5.footprint())
            assertEquals(expectedConfiguration, runscriptTutorialV5.configuration())
        }
        Map callerCopy = runscriptTutorialV5.configuration()
        callerCopy.threads = 99
        assertEquals(expectedConfiguration, runscriptTutorialV5.configuration())
    }

    @Test
    void tracksEveryActuallyLoadedStockScript() {
        List expected = ['noisemodelling/wps/Import_and_Export/Import_File.groovy',
                'noisemodelling/wps/NoiseModelling/Noise_level_from_traffic.groovy',
                'noisemodelling/wps/Import_and_Export/Export_Table.groovy']
        assertEquals(expected, runscriptTutorialV5.requiredStockScripts())
        File stock = new File(temporary, 'fake-stock')
        Map pins = [:]
        expected.eachWithIndex { relative, index ->
            File file = new File(stock, relative)
            file.parentFile.mkdirs()
            byte[] bytes = "def marker() { return 'script-${index}-é' }\n".getBytes(StandardCharsets.UTF_8)
            file.bytes = bytes
            pins[relative] = hash(bytes)
        }
        Map originalPins = new LinkedHashMap(pins)
        expected.eachWithIndex { relative, index ->
            Script parsed = runscriptTutorialV5.loadTrackedScript(new GroovyShell(), stock, relative, pins)
            assertEquals("script-${index}-é".toString(), parsed.invokeMethod('marker', null))
        }
        assertEquals(originalPins, pins)
    }

    @Test
    void refusesMissingUndeclaredAndChangedScriptBytes() {
        File stock = new File(temporary, 'fake-stock')
        String relative = runscriptTutorialV5.requiredStockScripts()[0]
        File file = new File(stock, relative)
        file.parentFile.mkdirs()
        byte[] original = 'def marker() { return 1 }\n'.getBytes(StandardCharsets.UTF_8)
        file.bytes = original
        Map pins = [(relative): hash(original)]
        expectFailure { runscriptTutorialV5.loadTrackedScript(new GroovyShell(), stock, relative, [:]) }
        expectFailure { runscriptTutorialV5.loadTrackedScript(new GroovyShell(), stock, 'other.groovy', pins) }
        file.text = 'def marker() { return 2 }\n'
        expectFailure { runscriptTutorialV5.loadTrackedScript(new GroovyShell(), stock, relative, pins) }
        file.delete()
        expectFailure { runscriptTutorialV5.loadTrackedScript(new GroovyShell(), stock, relative, pins) }
    }

    @Test
    void parsesTheSameUtf8BytesThatWereHashedRatherThanReopeningTheFile() {
        File stock = new File(temporary, 'fake-stock')
        String relative = runscriptTutorialV5.requiredStockScripts()[0]
        File file = new File(stock, relative)
        file.parentFile.mkdirs()
        byte[] before = "def marker() { return 'before-é' }\n".getBytes(StandardCharsets.UTF_8)
        file.bytes = before
        GroovyShell changingShell = new GroovyShell() {
            @Override
            Script parse(String text, String filename) {
                file.setText("def marker() { return 'after' }\n", 'UTF-8')
                return super.parse(text, filename)
            }
        }
        Script parsed = runscriptTutorialV5.loadTrackedScript(changingShell, stock, relative, [(relative): hash(before)])
        assertEquals('before-é', parsed.invokeMethod('marker', null))
        assertTrue(file.getText('UTF-8').contains("'after'"))
    }

    private File receiverCsv(String directory, String data, Map changes = [:], List columns = null) {
        File folder = new File(temporary, directory)
        folder.mkdirs()
        File csv = new File(folder, 'RECEIVERS_LEVEL.csv')
        csv.setText(data, 'UTF-8')
        Map identity = [schema: 1, 'case': 'tutorial-barrier', height_m: 3,
                engine_version: 'v5.0.1', source_resource_sha256: ['fixture.csv': 'a' * 64],
                stock_script_sha256: runscriptTutorialV5.requiredStockScripts().collectEntries { [(it): 'b' * 64] },
                configuration: runscriptTutorialV5.configuration(), screen_wkt: runscriptTutorialV5.footprint(),
                screen_id: 1702, srid: 2154,
                counts: [receivers: 2], row_count: 2,
                columns: columns ?: csvColumns(),
                complete_native_export: true]
        identity.putAll(changes)
        new File(folder, 'IDENTITY.json').setText(JsonOutput.toJson(identity) + '\n', 'UTF-8')
        return csv
    }

    private Map compareCommand(List arguments, int expectedExit) {
        File root = new File(System.getProperty('nmBenchmarkRoot', '.')).canonicalFile
        String python = System.getProperty('nmPython', 'python3')
        File output = File.createTempFile('comparison-', '.log', temporary)
        List command = [python, new File(root, 'compare_versions.py').absolutePath] + arguments
        Process process = new ProcessBuilder(command as String[]).directory(root)
                .redirectErrorStream(true).redirectOutput(output).start()
        boolean completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            fail('Native compare_versions.py fixture check exceeded 30 seconds')
        }
        String text = output.getText('UTF-8')
        assertEquals(text, expectedExit, process.exitValue())
        if (expectedExit == 0 || expectedExit == 1) {
            Object result = new JsonSlurper().parseText(text)
            assertTrue('Native comparison must return JSON', result instanceof Map)
            return result as Map
        }
        return [:]
    }

    private static List csvColumns() {
        ['IDRECEIVER', 'PERIOD', 'THE_GEOM', 'LAEQ', 'LEQ', 'HZ63']
    }

    private static String csvWithColumns(List columns) {
        List rows = [[IDRECEIVER: '1', PERIOD: 'D', THE_GEOM: '"SRID=2154;POINT Z (1 2 3)"',
                      LAEQ: '55.0', LEQ: '56.0', HZ63: '60.0'],
                     [IDRECEIVER: '2', PERIOD: 'D', THE_GEOM: '"SRID=2154;POINT Z (4 5 6)"',
                      LAEQ: '56.0', LEQ: '57.0', HZ63: '61.0']]
        return columns.join(',') + '\n' +
                rows.collect { row -> columns.collect { row[it] }.join(',') }.join('\n') + '\n'
    }

    private static String validCsv() {
        csvWithColumns(csvColumns())
    }

    @Test
    void nativeComparisonAcceptsACompleteIdenticalExport() {
        File first = receiverCsv('left', validCsv())
        File second = receiverCsv('right', validCsv())
        compareCommand(['--check-receivers-csv', first.absolutePath], 0)
        compareCommand(['--compare-receivers-csv', first.absolutePath, second.absolutePath], 0)
    }

    @Test
    void nativeComparisonDetectsDifferencesOutsideLaeqAndByteOnlyDifferences() {
        File first = receiverCsv('left', validCsv())
        File changedField = receiverCsv('field-change', validCsv().replace('60.0', '60.5'))
        File changedBytes = receiverCsv('byte-change', validCsv().replace('55.0', '55.00'))
        compareCommand(['--compare-receivers-csv', first.absolutePath, changedField.absolutePath], 1)
        compareCommand(['--compare-receivers-csv', first.absolutePath, changedBytes.absolutePath], 1)
    }

    @Test
    void nativeComparisonRejectsDuplicateReceiverPeriodAndNonfiniteNonLaeqField() {
        File duplicate = receiverCsv('duplicate', validCsv().replace('2,D', '1,D'), [counts: [receivers: 1]])
        File nan = receiverCsv('nan', validCsv().replace('60.0', 'NaN'))
        File infinity = receiverCsv('infinity', validCsv().replace('61.0', 'Infinity'))
        [duplicate, nan, infinity].each { csv ->
            compareCommand(['--check-receivers-csv', csv.absolutePath], 2)
        }
    }

    @Test
    void nativeComparisonRejectsMissingCsvIdentityAndRequiredColumns() {
        compareCommand(['--check-receivers-csv', new File(temporary, 'absent.csv').absolutePath], 2)
        File noIdentity = receiverCsv('no-identity', validCsv())
        new File(noIdentity.parentFile, 'IDENTITY.json').delete()
        compareCommand(['--check-receivers-csv', noIdentity.absolutePath], 2)
        ['IDRECEIVER', 'PERIOD', 'THE_GEOM', 'LAEQ', 'LEQ'].each { missing ->
            List remaining = csvColumns().findAll { it != missing }
            File absentColumn = receiverCsv('missing-' + missing, csvWithColumns(remaining), [:], remaining)
            compareCommand(['--check-receivers-csv', absentColumn.absolutePath], 2)
        }
        File missingRow = receiverCsv('missing-row', validCsv().readLines().take(2).join('\n') + '\n')
        File shortRow = receiverCsv('short-row', validCsv().replace('57.0,61.0', '57.0'))
        [missingRow, shortRow].each { csv ->
            compareCommand(['--check-receivers-csv', csv.absolutePath], 2)
        }
    }

    @Test
    void nativeComparisonRejectsDuplicateIdentityKeysAndMismatchedFixture() {
        File first = receiverCsv('left', validCsv())
        File duplicateKey = receiverCsv('duplicate-key', validCsv())
        File identity = new File(duplicateKey.parentFile, 'IDENTITY.json')
        identity.setText(identity.getText('UTF-8').replace('"schema":1', '"schema":1,"schema":1'), 'UTF-8')
        compareCommand(['--check-receivers-csv', duplicateKey.absolutePath], 2)
        File differentHeight = receiverCsv('height', validCsv(), [height_m: 5])
        File differentConfig = receiverCsv('configuration', validCsv(),
                [configuration: runscriptTutorialV5.configuration() + [threads: 2]])
        [differentHeight, differentConfig].each { csv ->
            compareCommand(['--compare-receivers-csv', first.absolutePath, csv.absolutePath], 2)
        }
        ['screen_id', 'srid'].each { missing ->
            File csv = receiverCsv('missing-' + missing, validCsv())
            File sidecar = new File(csv.parentFile, 'IDENTITY.json')
            Map incomplete = new JsonSlurper().parse(sidecar) as Map
            incomplete.remove(missing)
            sidecar.setText(JsonOutput.toJson(incomplete) + '\n', 'UTF-8')
            compareCommand(['--check-receivers-csv', csv.absolutePath], 2)
        }
        [[screen_id: 1703], [srid: 4326], [screen_id: 1702.0d], [srid: true]].eachWithIndex { changes, index ->
            File csv = receiverCsv('wrong-identity-' + index, validCsv(), changes)
            compareCommand(['--check-receivers-csv', csv.absolutePath], 2)
        }
        File differentCounts = receiverCsv('source-counts', validCsv(), [counts: [receivers: 3]])
        compareCommand(['--check-receivers-csv', differentCounts.absolutePath], 0)
        compareCommand(['--compare-receivers-csv', first.absolutePath, differentCounts.absolutePath], 2)
    }

    @Test
    void nativeComparisonRequiresSameVersionScriptPinsButAllowsCrossVersionChanges() {
        File first = receiverCsv('left', validCsv())
        Map changedPins = runscriptTutorialV5.requiredStockScripts().collectEntries { [(it): 'c' * 64] }
        File changedSameVersion = receiverCsv('same-version-different-scripts', validCsv(),
                [stock_script_sha256: changedPins])
        File changedVersion = receiverCsv('other-version', validCsv(),
                [engine_version: 'v5.0.0', stock_script_sha256: changedPins])
        compareCommand(['--compare-receivers-csv', first.absolutePath, changedSameVersion.absolutePath], 2)
        compareCommand(['--compare-receivers-csv', first.absolutePath, changedVersion.absolutePath], 0)
    }
}
