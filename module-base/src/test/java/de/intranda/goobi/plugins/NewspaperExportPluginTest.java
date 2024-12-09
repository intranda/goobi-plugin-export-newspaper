package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.MatchResult;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.ProjectFileGroup;
import org.goobi.beans.Ruleset;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginType;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.persistence.managers.MetadataManager;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ MetadatenHelper.class, VariableReplacer.class, ConfigurationHelper.class, ConfigPlugins.class, MetadataManager.class })
@PowerMockIgnore({ "javax.management.*", "javax.xml.*", "org.xml.*", "org.w3c.*", "javax.net.ssl.*", "jdk.internal.reflect.*" })
public class NewspaperExportPluginTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();
    private File tempFolder;
    private File exportFolder;
    private static String resourcesFolder;

    private File processDirectory;
    private File metadataDirectory;
    private Process process;
    private Step step;
    private Prefs prefs;

    @BeforeClass
    public static void setUpClass() {
        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse

        if (!Files.exists(Paths.get(resourcesFolder))) {
            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
        }

        String log4jFile = resourcesFolder + "log4j2.xml"; // for junit tests in eclipse

        System.setProperty("log4j.configurationFile", log4jFile);
    }

    @Before
    public void setUp() throws Exception {
        tempFolder = folder.newFolder("tmp");
        exportFolder = folder.newFolder("destination");
        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse

        if (!Files.exists(Paths.get(resourcesFolder))) {
            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
        }
        PowerMock.mockStatic(ConfigPlugins.class);
        EasyMock.expect(ConfigPlugins.getPluginConfig(EasyMock.anyString())).andReturn(getConfig()).anyTimes();
        PowerMock.replay(ConfigPlugins.class);
        process = prepareProcess();

    }

    @Test
    public void testConstructor() {
        NewspaperExportPlugin plugin = new NewspaperExportPlugin();
        assertNotNull(plugin);
    }

    @Test
    public void testPluginTitle() {
        NewspaperExportPlugin plugin = new NewspaperExportPlugin();
        assertEquals("intranda_export_newspaper", plugin.getTitle());
    }

    @Test
    public void testPluginType() {
        NewspaperExportPlugin plugin = new NewspaperExportPlugin();
        assertEquals(PluginType.Export, plugin.getType());
    }

    @Test
    public void testProblems() {
        NewspaperExportPlugin plugin = new NewspaperExportPlugin();
        assertNull(plugin.getProblems());
    }

    @Test
    public void testExportFulltext() {
        NewspaperExportPlugin plugin = new NewspaperExportPlugin();
        assertFalse(plugin.isExportFulltext());
        plugin.setExportFulltext(true);
        assertTrue(plugin.isExportFulltext());

    }

    @Test
    public void testExportImages() {
        NewspaperExportPlugin plugin = new NewspaperExportPlugin();
        assertFalse(plugin.isExportImages());
        plugin.setExportImages(true);
        assertTrue(plugin.isExportImages());
    }

    //    @Test
    public void testExport() throws Exception {
        NewspaperExportPlugin plugin = new NewspaperExportPlugin();
        plugin.startExport(process);
    }

    private XMLConfiguration getConfig() {
        String file = "plugin_intranda_export_newspaper.xml";
        XMLConfiguration config = new XMLConfiguration();
        config.setDelimiterParsingDisabled(true);
        try {
            config.load(resourcesFolder + file);
        } catch (ConfigurationException e) {
        }
        config.setReloadingStrategy(new FileChangedReloadingStrategy());
        // overwrite export destination with
        SubnodeConfiguration sub = config.configurationAt("config.export");
        sub.setProperty("exportFolder", exportFolder.toString() + "/");
        sub.setProperty("exportImageFolder", exportFolder.toString() + "/$(meta.CatalogIDDigital)_tif/");
        sub.setProperty("exportAltoFolder", exportFolder.toString() + "/$(meta.CatalogIDDigital)_alto/");
        return config;
    }

    private Process prepareProcess() throws Exception {

        // create folder structure and copy files
        metadataDirectory = folder.newFolder("metadata");
        processDirectory = new File(metadataDirectory + File.separator + "1");
        processDirectory.mkdirs();

        String metadataDirectoryName = metadataDirectory.getAbsolutePath() + File.separator;
        Path metaSource = Paths.get(resourcesFolder, "meta.xml");
        Path metaTarget = Paths.get(processDirectory.getAbsolutePath(), "meta.xml");
        Files.copy(metaSource, metaTarget);
        Path anchorSource = Paths.get(resourcesFolder, "meta_anchor.xml");
        Path anchorTarget = Paths.get(processDirectory.getAbsolutePath(), "meta_anchor.xml");
        Files.copy(anchorSource, anchorTarget);

        // prepare images + alto files
        File imageFolder = new File(processDirectory + File.separator + "images" + File.separator + "processtitle_media");
        imageFolder.mkdirs();

        File altoFolder = new File(processDirectory + File.separator + "ocr" + File.separator + "processtitle_xml");
        altoFolder.mkdirs();

        Path sourceImage = Paths.get(resourcesFolder, "00000005.tif");
        Path sourceAlto = Paths.get(resourcesFolder, "00000005.xml");

        for (int i = 1; i < 53; i++) {
            String basename;
            if (i < 10) {
                basename = "0000000" + i;
            } else {
                basename = "000000" + i;
            }
            Path destinationImage = Paths.get(imageFolder.toString(), basename + ".tif");
            Path destinationAlto = Paths.get(altoFolder.toString(), basename + ".xml");
            Files.copy(sourceImage, destinationImage);
            Files.copy(sourceAlto, destinationAlto);
        }

        // mock configuration
        PowerMock.mockStatic(ConfigurationHelper.class);
        ConfigurationHelper configurationHelper = EasyMock.createMock(ConfigurationHelper.class);
        EasyMock.expect(ConfigurationHelper.getInstance()).andReturn(configurationHelper).anyTimes();
        EasyMock.expect(configurationHelper.getMetsEditorLockingTime()).andReturn(1800000l).anyTimes();
        EasyMock.expect(configurationHelper.isAllowWhitespacesInFolder()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.useS3()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isUseProxy()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isUseMasterDirectory()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isCreateMasterDirectory()).andReturn(false).anyTimes();

        EasyMock.expect(configurationHelper.getGoobiContentServerTimeOut()).andReturn(60000).anyTimes();
        EasyMock.expect(configurationHelper.getMetadataFolder()).andReturn(metadataDirectoryName).anyTimes();
        EasyMock.expect(configurationHelper.getNumberOfMetaBackups()).andReturn(0).anyTimes();
        EasyMock.expect(configurationHelper.getRulesetFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getConfigurationFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getTemporaryFolder()).andReturn(tempFolder.getAbsolutePath()).anyTimes();
        EasyMock.expect(configurationHelper.getProcessImagesMainDirectoryName()).andReturn("processtitle_media").anyTimes();
        EasyMock.expect(configurationHelper.getProcessOcrAltoDirectoryName()).andReturn("processtitle_xml").anyTimes();
        EasyMock.expect(configurationHelper.getScriptsFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getGoobiFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.replay(configurationHelper);
        PowerMock.replay(ConfigurationHelper.class);
        PowerMock.mockStatic(VariableReplacer.class);

        // for each issue
        for (int i = 0; i < 14; i++) {
            // variable replacer for mets parameter
            EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject())).andReturn("mets parameter").times(14);

            // current identifier
            EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject()))
                    .andAnswer(
                            new IAnswer<String>() {
                                @Override
                                public String answer() throws Throwable {
                                    return EasyMock.getCurrentArgument(0);
                                }
                            })
                    .times(2);

            // media and ocr folder
            EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject())).andReturn("processtitle_media");
            EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject())).andReturn("processtitle_xml");
        }
        List<MatchResult> results = new ArrayList<>();
        EasyMock.expect(VariableReplacer.findRegexMatches(EasyMock.anyString(), EasyMock.anyString())).andReturn(results).anyTimes();
        PowerMock.replay(VariableReplacer.class);
        PowerMock.mockStatic(MetadataManager.class);
        MetadataManager.updateMetadata(1, Collections.emptyMap());
        MetadataManager.updateJSONMetadata(1, Collections.emptyMap());
        PowerMock.replay(MetadataManager.class);

        Process proc = new Process();
        proc.setId(1);
        proc.setTitel("processTitle");
        Ruleset r = new Ruleset();
        r.setDatei("ruleset_newspaper.xml");
        r.setTitel("ruleset_newspaper.xml");
        proc.setRegelsatz(r);

        prefs = new Prefs();
        prefs.loadPrefs(resourcesFolder + "ruleset_newspaper.xml");
        Fileformat ff = new MetsMods(prefs);
        ff.read(metaTarget.toString());

        // TODO supplements

        PowerMock.mockStatic(MetadatenHelper.class);
        EasyMock.expect(MetadatenHelper.getMetaFileType(EasyMock.anyString())).andReturn("mets").anyTimes();
        EasyMock.expect(MetadatenHelper.getFileformatByName(EasyMock.anyString(), EasyMock.anyObject())).andReturn(ff).anyTimes();
        EasyMock.expect(MetadatenHelper.getMetadataOfFileformat(EasyMock.anyObject(), EasyMock.anyBoolean()))
                .andReturn(Collections.emptyMap())
                .anyTimes();
        PowerMock.replay(MetadatenHelper.class);

        step = new Step();
        step.setId(1);
        step.setTitel("step title");
        List<Step> steplist = new ArrayList<>();
        steplist.add(step);
        proc.setSchritte(steplist);

        Project project = new Project();
        project.setId(1);
        proc.setProjekt(project);
        project.setDmsImportImagesPath(tempFolder.toString());

        List<ProjectFileGroup> fileGroups = new ArrayList<>();
        ProjectFileGroup images = new ProjectFileGroup();
        images.setPath("$(meta.CatalogIDDigital)_tif/");
        images.setMimetype("image/tiff");
        images.setSuffix("tif");
        images.setName("DEFAULT");
        images.setProject(project);
        images.setUseOriginalFiles(true);
        fileGroups.add(images);
        ProjectFileGroup alto = new ProjectFileGroup();
        alto.setPath("$(meta.CatalogIDDigital)_xml/");
        alto.setMimetype("application/xml");
        alto.setSuffix("xml");
        alto.setName("FULLTEXT");
        alto.setProject(project);
        fileGroups.add(alto);
        project.setFilegroups(fileGroups);

        return proc;
    }

}
