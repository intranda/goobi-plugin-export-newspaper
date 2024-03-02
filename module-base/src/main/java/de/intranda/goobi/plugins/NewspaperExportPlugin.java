package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.ProjectFileGroup;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.XmlTools;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Corporate;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.ExportFileformat;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataType;
import ugh.dl.NamePart;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.dl.VirtualFileGroup;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsModsImportExport;

@PluginImplementation
@Log4j2
public class NewspaperExportPlugin implements IExportPlugin, IPlugin {

    private static final long serialVersionUID = 7249755051578684102L;
    @Getter
    private String title = "intranda_export_newspaper";
    @Getter
    private PluginType type = PluginType.Export;

    @Getter
    private List<String> problems;

    @Setter
    private boolean exportFulltext;
    @Setter
    private boolean exportImages;

    private boolean addFileExtension = true;

    private static final Namespace metsNamespace = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
    private static final Namespace xlink = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        String benutzerHome = process.getProjekt().getDmsImportImagesPath();
        return startExport(process, benutzerHome);
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
            SwapException, DAOException, TypeNotAllowedForParentException {
        problems = new ArrayList<>();

        String projectName = process.getProjekt().getTitel();
        String goobiId = String.valueOf(process.getId());

        Prefs prefs = process.getRegelsatz().getPreferences();
        XMLConfiguration globalSettings = ConfigPlugins.getPluginConfig(title);
        globalSettings.setExpressionEngine(new XPathExpressionEngine());

        SubnodeConfiguration projectSettings = null;
        // order of configuration is:
        // 1.) project name and step name matches
        // 2.) step name matches and project is *
        // 3.) project name matches and step name is *
        // 4.) project name and step name are *
        try {
            projectSettings = globalSettings.configurationAt("//config[./project = '" + projectName + "']");
        } catch (IllegalArgumentException e) {
            try {
                projectSettings = globalSettings.configurationAt("//config[./project = '*']");
            } catch (IllegalArgumentException e1) {
                log.error(e1);
            }
        }

        MetadataType zdbIdAnalogType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/zdbidanalog"));
        MetadataType zdbIdDigitalType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/zdbiddigital"));
        MetadataType purlType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/purl"));

        MetadataType identifierType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/identifier"));
        MetadataType issueDateType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/issueDate"));
        MetadataType yearDateType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/yearDate"));

        MetadataType labelType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/titleLabel"));
        MetadataType mainTitleType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/modsTitle"));

        MetadataType issueNumberType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/issueNumber"));
        MetadataType sortNumberType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/sortNumber"));

        MetadataType languageType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/language"));
        MetadataType locationType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/location"));
        MetadataType accessConditionType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/licence"));

        MetadataType resourceType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/resourceType"));

        MetadataType anchorIdType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/anchorId"));
        MetadataType anchorTitleType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/anchorTitle"));
        MetadataType anchorZDBIdDigitalType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/anchorZDBIdDigital"));

        DocStructType newspaperType = prefs.getDocStrctTypeByName(globalSettings.getString("/docstruct/newspaper"));
        DocStructType yearType = prefs.getDocStrctTypeByName(globalSettings.getString("/docstruct/year"));
        DocStructType monthType = prefs.getDocStrctTypeByName(globalSettings.getString("/docstruct/month"));
        DocStructType dayType = prefs.getDocStrctTypeByName(globalSettings.getString("/docstruct/day"));
        DocStructType issueType = prefs.getDocStrctTypeByName(globalSettings.getString("/docstruct/issue"));

        DocStructType newspaperStubType = prefs.getDocStrctTypeByName(globalSettings.getString("/docstruct/newspaperStub"));

        boolean subfolderPerIssue = projectSettings.getBoolean("/export/subfolderPerIssue", false);
        exportImages = projectSettings.getBoolean("/export/images", false);
        String metsResolverUrl = projectSettings.getString("/metsUrl");
        addFileExtension = projectSettings.getBoolean("/metsUrl/@addFileExtension", false);
        String piResolverUrl = projectSettings.getString("/resolverUrl");

        Path tmpExportFolder = Files.createTempDirectory("mets_export");

        String finalExportFolder = projectSettings.getString("/export/exportFolder");
        if (!finalExportFolder.endsWith("/")) {
            finalExportFolder = finalExportFolder + "/";
        }

        // read fileformat
        Fileformat fileformat = process.readMetadataFile();
        DigitalDocument digitalDocument = fileformat.getDigitalDocument();
        DocStruct logical = digitalDocument.getLogicalDocStruct();
        DocStruct oldPhysical = digitalDocument.getPhysicalDocStruct();

        VariableReplacer vp = new VariableReplacer(digitalDocument, prefs, process, null);
        List<ProjectFileGroup> myFilegroups = getProjectFileGroups(projectSettings, process.getProjekt().getFilegroups());

        // check if it is a newspaper
        if (!logical.getType().isAnchor()) {
            problems.add(logical.getType().getName() + " has the wrong type. It is not an anchor.");
            return false;
        }
        String zdbIdAnalog = null;
        String zdbIdDigital = null;
        String identifier = null;
        String titleLabel = null;
        String mainTitle = null;
        String language = null;
        String location = null;
        String accessCondition = null;

        for (Metadata md : logical.getAllMetadata()) {
            //  get zdb id
            if (md.getType().equals(zdbIdAnalogType)) {
                zdbIdAnalog = md.getValue();
            }
            if (md.getType().equals(zdbIdDigitalType)) {
                zdbIdDigital = md.getValue();
            }
            //  get identifier
            else if (md.getType().equals(identifierType)) {
                identifier = md.getValue();
            } else if (md.getType().equals(labelType)) {
                titleLabel = md.getValue();
            } else if (md.getType().equals(mainTitleType)) {
                mainTitle = md.getValue();
            } else if (md.getType().equals(languageType)) {
                language = md.getValue();
            } else if (md.getType().equals(locationType)) {
                location = md.getValue();
            } else if (md.getType().equals(accessConditionType)) {
                accessCondition = md.getValue();
            }
        }
        if (StringUtils.isBlank(mainTitle) && StringUtils.isNotBlank(titleLabel)) {
            Metadata md = new Metadata(mainTitleType);
            md.setValue(titleLabel);
            logical.addMetadata(md);
        }

        if (StringUtils.isBlank(zdbIdAnalog) || StringUtils.isBlank(zdbIdDigital) || StringUtils.isBlank(identifier)) {
            problems.add("Export aborted, ZDB id or record id are missing");
            return false;
        }

        DocStruct volume = logical.getAllChildren().get(0);
        String volumeLabel = null;
        String volumeTitle = null;
        String publicationYear = null;
        String sortNumber = null;
        String issueNumber = null;

        for (Metadata md : volume.getAllMetadata()) {
            // get current year
            if (md.getType().equals(yearDateType)) {
                publicationYear = md.getValue();
            }
            if (md.getType().equals(labelType)) {
                volumeLabel = md.getValue();
            }
            if (md.getType().equals(mainTitleType)) {
                volumeTitle = md.getValue();
            }
            if (md.getType().equals(sortNumberType)) {
                sortNumber = md.getValue();
            }
            if (md.getType().equals(issueNumberType)) {
                issueNumber = md.getValue();
            }
            if (language == null && md.getType().equals(languageType)) {
                language = md.getValue();
            }
            if (location == null && md.getType().equals(locationType)) {
                location = md.getValue();
            }
            if (accessCondition == null && md.getType().equals(accessConditionType)) {
                accessCondition = md.getValue();
            }
        }

        if (StringUtils.isBlank(volumeTitle) && StringUtils.isNotBlank(volumeLabel)) {
            try {
                Metadata md = new Metadata(mainTitleType);
                md.setValue(volumeLabel);
                volume.addMetadata(md);
            } catch (UGHException e) {
                log.info(e);
            }
        }

        if (StringUtils.isBlank(sortNumber) && StringUtils.isNotBlank(issueNumber) && StringUtils.isNumeric(issueNumber)) {
            try {
                Metadata md = new Metadata(sortNumberType);
                md.setValue(issueNumber);
                volume.addMetadata(md);
            } catch (UGHException e) {
                log.info(e);
            }
        }

        // list all issues
        List<DocStruct> issues = volume.getAllChildren();

        // create new anchor file for newspaper
        // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Gesamtaufnahme+Zeitung+1.0

        ExportFileformat newspaperExport = new MetsModsImportExport(prefs);

        DigitalDocument anchorDigitalDocument = new DigitalDocument();
        newspaperExport.setDigitalDocument(anchorDigitalDocument);
        String anchor = projectSettings.getString("/metsPointerPathAnchor", process.getProjekt().getMetsPointerPathAnchor());
        anchor = vp.replace(anchor);
        newspaperExport.setMptrAnchorUrl(anchor);
        String pointer = projectSettings.getString("/metsPointerPath", process.getProjekt().getMetsPointerPath());
        pointer = vp.replace(pointer);
        setMetsParameter(process, projectSettings, goobiId, vp, pointer, anchor, newspaperExport);

        DocStruct newspaper = copyDocstruct(newspaperType, logical, anchorDigitalDocument);
        anchorDigitalDocument.setLogicalDocStruct(newspaper);

        // create volume for year
        // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Jahrgang+Zeitung+1.0
        DocStruct yearVolume = copyDocstruct(yearType, volume, anchorDigitalDocument);
        if (newspaper == null || yearVolume == null) {
            return false;
        }
        if (StringUtils.isNotBlank(publicationYear)) {
            yearVolume.setOrderLabel(publicationYear);
        }
        String yearTitle = null;
        String yearIdentifier = null;
        for (Metadata md : yearVolume.getAllMetadata()) {
            if (md.getType().equals(labelType)) {
                yearTitle = md.getValue();
            } else if (md.getType().equals(identifierType)) {
                yearIdentifier = md.getValue();
            }
        }

        try {
            newspaper.addChild(yearVolume);
        } catch (TypeNotAllowedAsChildException e) {
            problems.add("Cannot add year to newspaper");
            log.error(e);
            return false;
        }

        for (DocStruct issue : issues) {
            // create issues, link issues to day
            // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Ausgabe+Zeitung+1.0
            // export each issue

            // check if required metadata is available, otherwise add it

            String issueLabel = null;
            String issueTitle = null;
            String issueNo = null;
            String issueSortingNumber = null;
            String issueLanguage = null;
            String issueLocation = null;
            String issueLicence = null;

            String issueIdentifier = null;
            String dateValue = null;
            String resource = null;
            String purl = null;
            String analogIssueZdbId = null;
            String digitalIssueZdbId = null;
            String anchorId = null;
            String anchorTitle = null;

            for (Metadata md : issue.getAllMetadata()) {
                if (md.getType().equals(anchorZDBIdDigitalType)) {
                    digitalIssueZdbId = md.getValue();
                }
                if (md.getType().equals(zdbIdAnalogType)) {
                    analogIssueZdbId = md.getValue();
                }

                if (md.getType().equals(anchorIdType)) {
                    anchorId = md.getValue();
                }
                if (md.getType().equals(anchorTitleType)) {
                    anchorTitle = md.getValue();
                }

                if (md.getType().equals(identifierType)) {
                    issueIdentifier = md.getValue();
                }
                if (md.getType().equals(labelType)) {
                    issueLabel = md.getValue();
                }
                if (md.getType().equals(mainTitleType)) {
                    issueTitle = md.getValue();
                }
                if (md.getType().equals(issueNumberType)) {
                    issueNo = md.getValue();
                }
                if (md.getType().equals(sortNumberType)) {
                    issueSortingNumber = md.getValue();
                }
                if (md.getType().equals(issueDateType)) {
                    dateValue = md.getValue();
                }

                if (md.getType().equals(resourceType)) {
                    resource = md.getValue();
                }
                if (md.getType().equals(purlType)) {
                    purl = md.getValue();
                }

                if (md.getType().equals(languageType)) {
                    issueLanguage = md.getValue();
                }
                if (md.getType().equals(locationType)) {
                    issueLocation = md.getValue();
                }
                if (md.getType().equals(accessConditionType)) {
                    issueLicence = md.getValue();
                }

            }
            // copy metadata from anchor into the issue
            if (StringUtils.isBlank(issueTitle) && StringUtils.isNotBlank(issueLabel)) {
                try {
                    Metadata md = new Metadata(mainTitleType);
                    md.setValue(issueLabel);
                    issue.addMetadata(md);
                } catch (UGHException e) {
                    log.info(e);
                }
            }
            if (StringUtils.isBlank(issueSortingNumber) && StringUtils.isNotBlank(issueNo) && StringUtils.isNumeric(issueNo)) {
                Metadata md = new Metadata(sortNumberType);
                md.setValue(issueNo);
                issue.addMetadata(md);
                issueSortingNumber = issueNo;
            }
            if (StringUtils.isBlank(issueLanguage) && StringUtils.isNotBlank(language)) {
                Metadata md = new Metadata(languageType);
                md.setValue(language);
                issue.addMetadata(md);
            }

            if (StringUtils.isBlank(issueLocation) && StringUtils.isNotBlank(location)) {
                Metadata md = new Metadata(locationType);
                md.setValue(location);
                issue.addMetadata(md);
            }

            if (StringUtils.isBlank(issueLicence) && StringUtils.isNotBlank(accessCondition)) {
                Metadata md = new Metadata(accessConditionType);
                md.setValue(accessCondition);
                issue.addMetadata(md);
            }
            if (StringUtils.isBlank(analogIssueZdbId) && StringUtils.isNotBlank(zdbIdAnalog)) {
                Metadata md = new Metadata(zdbIdAnalogType);
                md.setValue(zdbIdAnalog);
                issue.addMetadata(md);
            }
            if (StringUtils.isBlank(digitalIssueZdbId) && StringUtils.isNotBlank(zdbIdDigital)) {
                Metadata md = new Metadata(anchorZDBIdDigitalType);
                md.setValue(zdbIdDigital);
                issue.addMetadata(md);
            }

            if (StringUtils.isBlank(issueIdentifier)) {
                issueIdentifier = identifier + "_" + dateValue + "_" + issueSortingNumber;
                Metadata md = new Metadata(identifierType);
                md.setValue(issueIdentifier);
                issue.addMetadata(md);
            }
            if (StringUtils.isBlank(resource)) {
                Metadata md = new Metadata(resourceType);
                md.setValue("text");
                issue.addMetadata(md);
            }

            if (StringUtils.isBlank(purl)) {
                Metadata md = new Metadata(purlType);
                md.setValue(piResolverUrl + issueIdentifier);
                issue.addMetadata(md);
            }

            if (StringUtils.isBlank(anchorId)) {
                Metadata md = new Metadata(anchorIdType);
                md.setValue(identifier);
                issue.addMetadata(md);
            }

            if (StringUtils.isBlank(anchorTitle)) {
                Metadata md = new Metadata(anchorTitleType);
                md.setValue(titleLabel);
                issue.addMetadata(md);
            }

            if (StringUtils.isBlank(dateValue)) {
                problems.add("Abort export, issue has no publication date");
                return false;
            }

            if (!dateValue.matches("\\d{4}-\\d{2}-\\d{2}")) {
                problems.add("Issue date " + dateValue + " has the wrong format. Expected is YYYY-MM-DD");
                return false;
            }

            if (StringUtils.isBlank(yearVolume.getOrderLabel())) {
                yearVolume.setOrderLabel(dateValue.substring(0, 4));
            }

            String monthValue = dateValue.substring(0, 7);

            DocStruct currentMonth = null;
            DocStruct currentDay = null;
            if (yearVolume.getAllChildren() != null) {
                for (DocStruct monthDocStruct : yearVolume.getAllChildren()) {
                    String currentDate = monthDocStruct.getOrderLabel();
                    if (monthValue.equals(currentDate)) {
                        currentMonth = monthDocStruct;
                        break;
                    }
                }
            }
            if (currentMonth == null) {
                try {
                    currentMonth = anchorDigitalDocument.createDocStruct(monthType);
                    currentMonth.setOrderLabel(monthValue);

                    yearVolume.addChild(currentMonth);
                } catch (TypeNotAllowedAsChildException e) {
                    log.error(e);
                }
            }
            if (currentMonth.getAllChildren() != null) {
                for (DocStruct dayDocStruct : currentMonth.getAllChildren()) {
                    String currentDate = dayDocStruct.getOrderLabel();
                    if (dateValue.equals(currentDate)) {
                        currentDay = dayDocStruct;
                        break;
                    }
                }
            }
            if (currentDay == null) {
                try {
                    currentDay = anchorDigitalDocument.createDocStruct(dayType);
                    currentDay.setOrderLabel(dateValue);
                    currentMonth.addChild(currentDay);
                } catch (TypeNotAllowedAsChildException e) {
                    log.error(e);
                }
            }
            try {
                DocStruct dummyIssue = anchorDigitalDocument.createDocStruct(issueType);
                dummyIssue.setOrderLabel(dateValue);
                currentDay.addChild(dummyIssue);
                if (issue.getAllMetadata() != null) {
                    for (Metadata md : issue.getAllMetadata()) {
                        if (md.getType().equals(labelType)) {
                            Metadata label = new Metadata(labelType);
                            label.setValue(md.getValue());
                            dummyIssue.addMetadata(label);

                        }
                    }
                }
                // create identifier if missing, add zdb id if missing
                if (addFileExtension) {
                    dummyIssue.setLink(metsResolverUrl + issueIdentifier + ".xml");
                } else {
                    dummyIssue.setLink(metsResolverUrl + issueIdentifier);
                }
                ExportFileformat issueExport = new MetsModsImportExport(prefs);

                DigitalDocument issueDigDoc = new DigitalDocument();
                issueExport.setDigitalDocument(issueDigDoc);

                setMetsParameter(process, projectSettings, goobiId, vp, pointer, anchor, issueExport);

                // create hierarchy for individual issue file

                // newspaper
                DocStruct dummyNewspaper = issueDigDoc.createDocStruct(newspaperStubType);
                if (addFileExtension) {
                    dummyNewspaper.setLink(metsResolverUrl + identifier + ".xml");
                } else {
                    dummyNewspaper.setLink(metsResolverUrl + identifier);
                }
                Metadata titleMd = null;
                try {
                    titleMd = new Metadata(labelType);
                    titleMd.setValue(titleLabel);
                    dummyNewspaper.addMetadata(titleMd);
                } catch (UGHException e) {
                    log.info(e);
                }
                // year
                DocStruct issueYear = issueDigDoc.createDocStruct(yearType);
                issueYear.setOrderLabel(dateValue.substring(0, 4));

                if (addFileExtension) {
                    issueYear.setLink(metsResolverUrl + yearIdentifier + ".xml");
                } else {
                    issueYear.setLink(metsResolverUrl + yearIdentifier);
                }
                titleMd = new Metadata(labelType);
                titleMd.setValue(yearTitle);
                try {
                    issueYear.addMetadata(titleMd);
                } catch (UGHException e) {
                    log.info(e);
                }
                dummyNewspaper.addChild(issueYear);

                // month
                DocStruct issueMonth = issueDigDoc.createDocStruct(monthType);
                issueMonth.setOrderLabel(monthValue);
                issueYear.addChild(issueMonth);
                // day
                DocStruct issueDay = issueDigDoc.createDocStruct(dayType);
                issueDay.setOrderLabel(dateValue);
                issueMonth.addChild(issueDay);

                // issue
                DocStruct newIssue = copyDocstruct(issueType, issue, issueDigDoc);
                issueDay.addChild(newIssue);

                issueDigDoc.setLogicalDocStruct(dummyNewspaper);

                // create physSequence
                DocStruct physicalDocstruct = issueDigDoc.createDocStruct(oldPhysical.getType());
                issueDigDoc.setPhysicalDocStruct(physicalDocstruct);

                // add images
                if (issue.getAllToReferences() != null) {
                    for (Reference ref : issue.getAllToReferences()) {
                        DocStruct oldPage = ref.getTarget();
                        String filename = Paths.get(oldPage.getImageName()).getFileName().toString();

                        DocStruct newPage = copyDocstruct(oldPage.getType(), oldPage, issueDigDoc);
                        if (newPage != null) {
                            newPage.setImageName(filename);
                            physicalDocstruct.addChild(newPage);

                            newIssue.addReferenceTo(newPage, "logical_physical");
                        }
                    }
                }

                boolean useOriginalFiles = false;

                if (myFilegroups != null && !myFilegroups.isEmpty()) {
                    for (ProjectFileGroup pfg : myFilegroups) {
                        if (pfg.isUseOriginalFiles()) {
                            useOriginalFiles = true;
                        }
                        // check if source files exists
                        if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
                            String foldername = process.getMethodFromName(pfg.getFolder());
                            if (foldername != null) {
                                Path folder = Paths.get(process.getMethodFromName(pfg.getFolder()));
                                if (folder != null && StorageProvider.getInstance().isFileExists(folder)
                                        && !StorageProvider.getInstance().list(folder.toString()).isEmpty()) {
                                    VirtualFileGroup v = createFilegroup(vp, pfg, subfolderPerIssue ? issueIdentifier : yearIdentifier);
                                    issueExport.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                                }
                            }
                        } else {
                            VirtualFileGroup v = createFilegroup(vp, pfg, subfolderPerIssue ? issueIdentifier : yearIdentifier);
                            issueExport.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                        }
                    }
                }

                if (useOriginalFiles) {
                    // check if media folder contains images
                    List<Path> filesInFolder = StorageProvider.getInstance().listFiles(process.getImagesTifDirectory(false));
                    if (!filesInFolder.isEmpty()) {
                        // compare image names with files in mets file
                        List<DocStruct> pages = issueDigDoc.getPhysicalDocStruct().getAllChildren();
                        if (pages != null && !pages.isEmpty()) {
                            for (DocStruct page : pages) {
                                Path completeNameInMets = Paths.get(page.getImageName());
                                String filenameInMets = completeNameInMets.getFileName().toString();
                                int dotIndex = filenameInMets.lastIndexOf('.');
                                if (dotIndex != -1) {
                                    filenameInMets = filenameInMets.substring(0, dotIndex);
                                }
                                for (Path imageNameInFolder : filesInFolder) {
                                    String imageName = imageNameInFolder.getFileName().toString();
                                    dotIndex = imageName.lastIndexOf('.');
                                    if (dotIndex != -1) {
                                        imageName = imageName.substring(0, dotIndex);
                                    }

                                    if (filenameInMets.equalsIgnoreCase(imageName)) {
                                        // found matching filename
                                        page.setImageName(imageNameInFolder.toString());
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }

                // export to configured folder
                String issueName = Paths.get(tmpExportFolder.toString(), issueIdentifier + ".xml").toString();
                issueExport.write(issueName);

                if (exportImages) {
                    String imagesFolder = process.getImagesTifDirectory(false);
                    String exportFolder = projectSettings.getString("/export/exportImageFolder")
                            .replace("$(meta.CatalogIDDigital)", subfolderPerIssue ? issueIdentifier : yearIdentifier);
                    List<DocStruct> pages = issueDigDoc.getPhysicalDocStruct().getAllChildren();
                    if (pages != null && !pages.isEmpty()) {
                        for (DocStruct page : pages) {
                            Path imageDestination = Paths.get(exportFolder, page.getImageName());
                            if (!StorageProvider.getInstance().isDirectory(imageDestination.getParent())) {
                                StorageProvider.getInstance().createDirectories(imageDestination.getParent());
                            }
                            StorageProvider.getInstance().copyFile(Paths.get(imagesFolder, page.getImageName()), imageDestination);
                        }
                    }
                }
                if (exportFulltext) {
                    String altoFolder = process.getOcrAltoDirectory();
                    String exportFolder = projectSettings.getString("/export/exportAltoFolder")
                            .replace("$(meta.CatalogIDDigital)", subfolderPerIssue ? issueIdentifier : yearIdentifier);
                    List<DocStruct> pages = issueDigDoc.getPhysicalDocStruct().getAllChildren();
                    if (pages != null && !pages.isEmpty()) {
                        for (DocStruct page : pages) {
                            Path imageDestination = Paths.get(exportFolder, page.getImageName());
                            if (!StorageProvider.getInstance().isDirectory(imageDestination.getParent())) {
                                StorageProvider.getInstance().createDirectories(imageDestination.getParent());
                            }
                            StorageProvider.getInstance().copyFile(Paths.get(altoFolder, page.getImageName()), imageDestination);
                        }
                    }

                }

            } catch (TypeNotAllowedAsChildException e) {
                log.error(e);
            }
        }

        // update/save generated data in goobi process
        process.writeMetadataFile(fileformat);

        String newspaperName = Paths.get(tmpExportFolder.toString(), yearIdentifier + ".xml").toString();
        newspaperExport.write(newspaperName);

        // rename anchor file
        Path anchorPath = Paths.get(newspaperName.replace(".xml", "_anchor.xml"));
        Path newAnchorPath = Paths.get(tmpExportFolder.toString(), identifier + ".xml");
        StorageProvider.getInstance().move(anchorPath, newAnchorPath);

        // check if newspaper anchor file exists in destination folder
        Path existingAnchor = Paths.get(finalExportFolder, identifier + ".xml");
        if (StorageProvider.getInstance().isFileExists(existingAnchor)) {
            // if yes: merge anchor with existing one
            // open anchor, run through structMap
            try {
                mergeAnchorWithVolumes(existingAnchor, newAnchorPath);
            } catch (JDOMException | IOException e) {
                log.error(e);
            }
            // remove anchor file from temp folder
            StorageProvider.getInstance().deleteFile(newAnchorPath);
        }

        // move all files to export folder
        List<Path> files = StorageProvider.getInstance().listFiles(tmpExportFolder.toString());
        for (Path file : files) {
            Path dest = Paths.get(finalExportFolder, file.getFileName().toString());
            StorageProvider.getInstance().move(file, dest);
        }

        // delete targetDir
        StorageProvider.getInstance().deleteDir(tmpExportFolder);
        return true;
    }

    private List<ProjectFileGroup> getProjectFileGroups(SubnodeConfiguration projectSettings, List<ProjectFileGroup> defaultFilegroups) {
        List<ProjectFileGroup> answer = new ArrayList<>();
        for (HierarchicalConfiguration hc : projectSettings.configurationsAt("/filegroups/filegroup")) {
            ProjectFileGroup pfg = new ProjectFileGroup();
            pfg.setName(hc.getString("@name"));
            pfg.setPath(hc.getString("@path"));
            pfg.setMimetype(hc.getString("@mimetype"));
            pfg.setSuffix(hc.getString("@suffix"));
            pfg.setFolder(hc.getString("@foldername"));
            pfg.setIgnoreMimetypes(hc.getString("@filesToIgnore"));
            pfg.setUseOriginalFiles(hc.getBoolean("@mimetypeFromFilename"));
        }
        return answer.isEmpty() ? defaultFilegroups : answer;
    }

    private void setMetsParameter(Process process, SubnodeConfiguration projectSettings, String goobiId, VariableReplacer vp, String pointer,
            String anchorPointer,
            ExportFileformat fileFormat) {
        fileFormat.setGoobiID(goobiId);

        fileFormat.setRightsOwner(vp.replace(projectSettings.getString("/rightsOwner", process.getProjekt().getMetsRightsOwner())));
        fileFormat.setRightsOwnerLogo(vp.replace(projectSettings.getString("/rightsOwnerLogo", process.getProjekt().getMetsRightsOwnerLogo())));
        fileFormat.setRightsOwnerSiteURL(vp.replace(projectSettings.getString("/rightsOwnerSiteURL", process.getProjekt().getMetsRightsOwnerSite())));
        fileFormat.setRightsOwnerContact(vp.replace(projectSettings.getString("/rightsOwnerContact", process.getProjekt().getMetsRightsOwnerMail())));
        fileFormat.setDigiprovPresentation(
                vp.replace(projectSettings.getString("/digiprovPresentation", process.getProjekt().getMetsDigiprovPresentation())));
        fileFormat.setDigiprovReference(vp.replace(projectSettings.getString("/digiprovReference", process.getProjekt().getMetsDigiprovReference())));
        fileFormat.setDigiprovPresentationAnchor(
                vp.replace(projectSettings.getString("/digiprovPresentationAnchor", process.getProjekt().getMetsDigiprovPresentationAnchor())));
        fileFormat.setDigiprovReferenceAnchor(
                vp.replace(projectSettings.getString("/digiprovReferenceAnchor", process.getProjekt().getMetsDigiprovReferenceAnchor())));

        fileFormat.setMetsRightsLicense(vp.replace(projectSettings.getString("/rightsLicense", process.getProjekt().getMetsRightsLicense())));
        fileFormat.setMetsRightsSponsor(vp.replace(projectSettings.getString("/rightsSponsor", process.getProjekt().getMetsRightsSponsor())));
        fileFormat.setMetsRightsSponsorLogo(
                vp.replace(projectSettings.getString("/rightsSponsorLogo", process.getProjekt().getMetsRightsSponsorLogo())));
        fileFormat.setMetsRightsSponsorSiteURL(
                vp.replace(projectSettings.getString("/rightsSponsorSiteURL", process.getProjekt().getMetsRightsSponsorSiteURL())));

        fileFormat.setPurlUrl(vp.replace(projectSettings.getString("/purl", process.getProjekt().getMetsPurl())));
        fileFormat.setContentIDs(vp.replace(projectSettings.getString("/contentIds", process.getProjekt().getMetsContentIDs())));
        fileFormat.setMptrUrl(pointer);
        fileFormat.setMptrAnchorUrl(anchorPointer);
        fileFormat.setWriteLocal(false);
    }

    private DocStruct copyDocstruct(DocStructType docstructType, DocStruct oldDocstruct, DigitalDocument dd) {

        // create new docstruct
        DocStruct newDocstruct = null;
        try {
            newDocstruct = dd.createDocStruct(docstructType);
        } catch (TypeNotAllowedForParentException e1) {
            log.error(e1);
            return null;
        }

        // copy metadata
        if (oldDocstruct.getAllMetadata() != null) {
            for (Metadata md : oldDocstruct.getAllMetadata()) {
                try {
                    Metadata clone = new Metadata(md.getType());
                    clone.setValue(md.getValue());
                    clone.setAutorityFile(md.getAuthorityID(), md.getAuthorityURI(), md.getAuthorityValue());
                    newDocstruct.addMetadata(clone);
                } catch (UGHException e) {
                    log.info(e);
                }
            }
        }

        // copy persons
        if (oldDocstruct.getAllPersons() != null) {
            for (Person p : oldDocstruct.getAllPersons()) {
                try {
                    Person clone = new Person(p.getType());
                    clone.setFirstname(p.getFirstname());
                    clone.setLastname(p.getLastname());
                    clone.setAutorityFile(p.getAuthorityID(), p.getAuthorityURI(), p.getAuthorityValue());
                    newDocstruct.addPerson(clone);
                } catch (UGHException e) {
                    log.info(e);
                }
            }
        }

        // copy corporates
        if (oldDocstruct.getAllCorporates() != null) {
            for (Corporate c : oldDocstruct.getAllCorporates()) {
                try {
                    Corporate clone = new Corporate(c.getType());
                    clone.setMainName(c.getMainName());

                    clone.setPartName(c.getPartName());
                    clone.setSubNames(c.getSubNames());
                    clone.setAutorityFile(c.getAuthorityID(), c.getAuthorityURI(), c.getAuthorityValue());
                    newDocstruct.addCorporate(clone);
                } catch (UGHException e) {
                    log.info(e);
                }
            }
        }

        // copy groups
        if (oldDocstruct.getAllMetadataGroups() != null) {
            for (MetadataGroup mg : oldDocstruct.getAllMetadataGroups()) {
                try {
                    MetadataGroup newMetadataGroup = cloneMetadataGroup(mg);
                    newDocstruct.addMetadataGroup(newMetadataGroup);
                } catch (UGHException e) {
                    log.info(e);
                }
            }
        }

        return newDocstruct;
    }

    private MetadataGroup cloneMetadataGroup(MetadataGroup inGroup) throws MetadataTypeNotAllowedException {
        MetadataGroup mg = new MetadataGroup(inGroup.getType());
        // copy metadata
        for (Metadata md : inGroup.getMetadataList()) {
            Metadata metadata = new Metadata(md.getType());
            metadata.setValue(md.getValue());
            if (StringUtils.isNotBlank(md.getAuthorityValue())) {
                metadata.setAutorityFile(md.getAuthorityID(), md.getAuthorityURI(), md.getAuthorityValue());
            }
            mg.addMetadata(metadata);
        }

        // copy persons
        for (Person p : inGroup.getPersonList()) {
            Person person = new Person(p.getType());
            person.setFirstname(p.getFirstname());
            person.setLastname(p.getLastname());
            person.setAutorityFile(p.getAuthorityID(), p.getAuthorityURI(), p.getAuthorityValue());
            if (p.getAdditionalNameParts() != null && !p.getAdditionalNameParts().isEmpty()) {
                for (NamePart np : p.getAdditionalNameParts()) {
                    NamePart newNamePart = new NamePart(np.getType(), np.getValue());
                    person.addNamePart(newNamePart);
                }
            }
            mg.addPerson(person);
        }

        // copy corporations
        for (Corporate c : inGroup.getCorporateList()) {
            Corporate corporate = new Corporate(c.getType());
            corporate.setMainName(c.getMainName());
            if (c.getSubNames() != null) {
                for (NamePart subName : c.getSubNames()) {
                    corporate.addSubName(subName);
                }
            }
            corporate.setPartName(c.getPartName());
            if (c.getAuthorityID() != null && c.getAuthorityURI() != null && c.getAuthorityValue() != null) {
                corporate.setAutorityFile(c.getAuthorityID(), c.getAuthorityURI(), c.getAuthorityValue());
            }
            mg.addCorporate(corporate);
        }

        // copy sub groups
        for (MetadataGroup subGroup : inGroup.getAllMetadataGroups()) {
            MetadataGroup copyOfSubGroup = cloneMetadataGroup(subGroup);
            mg.addMetadataGroup(copyOfSubGroup);
        }

        return mg;
    }

    private VirtualFileGroup createFilegroup(VariableReplacer variableRplacer, ProjectFileGroup projectFileGroup, String identifier) {
        VirtualFileGroup v = new VirtualFileGroup();
        v.setName(projectFileGroup.getName());
        v.setPathToFiles(variableRplacer.replace(projectFileGroup.getPath().replace("$(meta.CatalogIDDigital)", identifier)));
        v.setMimetype(projectFileGroup.getMimetype());
        v.setFileSuffix(projectFileGroup.getSuffix().trim());
        v.setFileExtensionsToIgnore(projectFileGroup.getIgnoreMimetypes());
        v.setIgnoreConfiguredMimetypeAndSuffix(projectFileGroup.isUseOriginalFiles());
        if ("PRESENTATION".equals(projectFileGroup.getName())) {
            v.setMainGroup(true);
        }
        return v;
    }

    private void mergeAnchorWithVolumes(Path oldAnchor, Path newAnchor) throws JDOMException, IOException {

        List<Volume> volumes = new ArrayList<>();
        SAXBuilder parser = XmlTools.getSAXBuilder();

        // alten anchor einlesen
        Document metsDoc = parser.build(oldAnchor.toFile());
        Element metsRoot = metsDoc.getRootElement();
        List<Element> structMapList = metsRoot.getChildren("structMap", metsNamespace);
        for (Element structMap1 : structMapList) {
            if (structMap1.getAttribute("TYPE") != null && "LOGICAL".equals(structMap1.getAttributeValue("TYPE"))) {
                readFilesFromAnchor(volumes, structMap1);
            }
        }

        // neuen anchor einlesen
        Document newMetsDoc = parser.build(newAnchor.toFile());
        Element newMetsRoot = newMetsDoc.getRootElement();

        List<Element> newStructMapList = newMetsRoot.getChildren("structMap", metsNamespace);
        for (Element structMap : newStructMapList) {
            if (structMap.getAttribute("TYPE") != null && "LOGICAL".equals(structMap.getAttributeValue("TYPE"))) {
                readFilesFromNewAnchor(volumes, structMap);
            }
        }

        Collections.sort(volumes, volumeComperator);

        for (Element structMap : structMapList) {
            if (structMap.getAttribute("TYPE") != null && "LOGICAL".equals(structMap.getAttributeValue("TYPE"))) {
                writeVolumesToAnchor(volumes, structMap);
            }
        }
        XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());

        try (FileOutputStream output = new FileOutputStream(oldAnchor.toFile())) {
            outputter.output(metsDoc, output);
        }

    }

    private void readFilesFromAnchor(List<Volume> volumes, Element structMap1) {
        Element anchorDiv = structMap1.getChild("div", metsNamespace);
        List<Element> volumeDivList = anchorDiv.getChildren("div", metsNamespace);
        for (Element volumeDiv : volumeDivList) {
            String label = "";
            String volumeType = "";
            String url = "";
            String contentids = "";
            String order = "";
            if (volumeDiv.getAttribute("LABEL") != null) {
                label = volumeDiv.getAttributeValue("LABEL");
            }
            if (volumeDiv.getAttribute("TYPE") != null) {
                volumeType = volumeDiv.getAttributeValue("TYPE");
            }
            if (volumeDiv.getAttribute("CONTENTIDS") != null) {
                contentids = volumeDiv.getAttributeValue("CONTENTIDS");
            }
            if (volumeDiv.getAttribute("ORDER") != null) {
                order = volumeDiv.getAttributeValue("ORDER");
            }
            Element mptr = volumeDiv.getChild("mptr", metsNamespace);
            url = mptr.getAttributeValue("href", xlink);
            boolean foundVolume = false;
            for (Volume vol : volumes) {
                if (vol.getUrl().equals(url)) {
                    foundVolume = true;
                }
            }
            if (!foundVolume) {
                Volume v = this.new Volume(label, volumeType, url, contentids, order);
                volumes.add(v);
            }
        }
    }

    /**
     * Reads and adds volumes from structMap which are not already present in provided volumes list to it
     *
     * @param volumes
     * @param structMap
     * @return
     */
    private boolean readFilesFromNewAnchor(List<Volume> volumes, Element structMap) {
        Element anchorDiv = structMap.getChild("div", metsNamespace);
        List<Element> volumeDivList = anchorDiv.getChildren("div", metsNamespace);
        for (Element volumeDiv : volumeDivList) {
            String label = "";
            String volumeType = "";
            String url = "";
            String contentids = "";
            String order = "";
            if (volumeDiv.getAttribute("LABEL") != null) {
                label = volumeDiv.getAttributeValue("LABEL");
            }
            if (volumeDiv.getAttribute("TYPE") != null) {
                volumeType = volumeDiv.getAttributeValue("TYPE");
            }
            if (volumeDiv.getAttribute("CONTENTIDS") != null) {
                contentids = volumeDiv.getAttributeValue("CONTENTIDS");
            }
            if (volumeDiv.getAttribute("ORDER") != null) {
                order = volumeDiv.getAttributeValue("ORDER");
            }
            Element mptr = volumeDiv.getChild("mptr", metsNamespace);
            url = mptr.getAttributeValue("href", xlink);
            Volume v = this.new Volume(label, volumeType, url, contentids, order);
            for (Volume vol : volumes) {
                if (vol.getUrl().replace(".xml", "").equals(url.replace(".xml", ""))) {
                    // reexport, muss nicht gemerged werden
                    return false;
                }
            }
            volumes.add(v);
        }
        return true;
    }

    /**
     * Adds the entry from the provided volumes List as sub elements to provided structMap Element
     * 
     * @param volumes
     * @param structMap
     */
    static void writeVolumesToAnchor(List<Volume> volumes, Element structMap) {
        Element anchorDiv = structMap.getChild("div", metsNamespace);
        // clearing anchor document
        anchorDiv.removeChildren("div", metsNamespace);
        // creating new children
        int logId = 1;
        for (Volume vol : volumes) {
            Element child = new Element("div", metsNamespace);

            String strId = padIdString(logId);
            child.setAttribute("ID", "LOG_" + strId);
            logId++;
            if (vol.getLabel() != null && vol.getLabel().length() > 0) {
                child.setAttribute("LABEL", vol.getLabel());
            }
            if (vol.getContentids() != null && vol.getContentids().length() > 0) {
                child.setAttribute("CONTENTIDS", vol.getContentids());
            }
            if (vol.getOrder() != null && vol.getOrder().length() > 0) {
                child.setAttribute("ORDER", vol.getOrder());
                child.setAttribute("ORDERLABEL", vol.getOrder());
            }
            child.setAttribute("TYPE", vol.getType());
            anchorDiv.addContent(child);
            Element mptr = new Element("mptr", metsNamespace);
            mptr.setAttribute("LOCTYPE", "URL");
            if (!vol.getUrl().endsWith(".xml")) {
                vol.setUrl(vol.getUrl() + ".xml");
            }
            mptr.setAttribute("href", vol.getUrl(), xlink);
            child.addContent(mptr);
        }
    }

    /**
     * adds 0 to front of passed id to ensure length of 4 digits
     *
     * @param logId
     * @return
     */
    private static String padIdString(int logId) {
        String strId = String.valueOf(logId);
        if (logId < 10) {
            strId = "000" + strId;
        } else if (logId < 100) {
            strId = "00" + strId;
        } else if (logId < 1000) {
            strId = "0" + strId;
        }
        return strId;
    }

    private static Comparator<Volume> volumeComperator = new Comparator<Volume>() { //NOSONAR

        @Override
        public int compare(Volume o1, Volume o2) {
            if (o1.getOrder() != null && o1.getOrder().length() > 0 && o2.getOrder() != null && o2.getOrder().length() > 0) {
                return o1.getOrder().compareToIgnoreCase(o2.getOrder());
            }
            return o1.getUrl().compareToIgnoreCase(o2.getUrl());
        }
    };

    @Data
    @AllArgsConstructor
    class Volume {
        private String label;
        private String type;
        private String url;
        private String contentids = null;
        private String order = null;
    }

}