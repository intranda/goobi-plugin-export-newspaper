package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
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
import ugh.dl.MetadataGroupType;
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
    @Getter
    private boolean exportFulltext;
    @Setter
    @Getter
    private boolean exportImages;

    private Prefs prefs;

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

        prefs = process.getRegelsatz().getPreferences();
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

        exportImages = projectSettings.getBoolean("/export/images", false);
        exportFulltext = projectSettings.getBoolean("/export/fulltext", false);

        String piResolverUrl = projectSettings.getString("/resolverUrl");

        Path tmpExportFolder = Files.createTempDirectory("mets_export");

        String finalExportFolder = projectSettings.getString("/export/exportFolder");
        if (!finalExportFolder.endsWith("/")) {
            finalExportFolder = finalExportFolder + "/";
        }

        // read fileformat
        Fileformat fileformat = process.readMetadataFile();
        DigitalDocument digitalDocument = fileformat.getDigitalDocument();

        DocStruct newspaper = digitalDocument.getLogicalDocStruct();
        DocStruct newspaperYear = newspaper.getAllChildren().get(0);
        List<DocStruct> issues = newspaperYear.getAllChildren();
        DocStruct oldPhysical = digitalDocument.getPhysicalDocStruct();

        // check if it is a newspaper
        if (!newspaper.getType().isAnchor()) {
            problems.add(newspaper.getType().getName() + " has the wrong type. It is not an anchor.");
            return false;
        }

        // validate mandatory fields, check if they are available or can be created
        MetadataType zdbIdAnalogType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/zdbidanalog"));
        MetadataType zdbIdDigitalType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/zdbiddigital"));
        MetadataType purlType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/purl"));
        //
        MetadataType identifierType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/identifier"));
        MetadataType issueDateType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/issueDate"));
        //
        MetadataType labelType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/titleLabel"));
        MetadataType mainTitleType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/modsTitle"));
        //
        MetadataType issueNumberType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/issueNumber"));
        MetadataType sortNumberType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/sortNumber"));
        //
        MetadataType languageType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/language"));
        MetadataType accessConditionType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/licence"));
        //
        MetadataType resourceType = prefs.getMetadataTypeByName(globalSettings.getString("/metadata/resourceType"));
        //
        //
        DocStructType issueType = prefs.getDocStrctTypeByName(globalSettings.getString("/docstruct/issue"));
        DocStructType pageType = prefs.getDocStrctTypeByName("page");

        VariableReplacer vp = new VariableReplacer(digitalDocument, prefs, process, null);
        List<ProjectFileGroup> myFilegroups = getProjectFileGroups(projectSettings, process.getProjekt().getFilegroups());

        String zdbIdAnalog = null;
        String zdbIdDigital = null;
        String identifier = null;
        String titleLabel = null;
        String mainTitle = null;
        String language = null;
        String accessCondition = null;

        for (Metadata md : newspaper.getAllMetadata()) {
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
            } else if (md.getType().equals(accessConditionType)) {
                accessCondition = md.getValue();
            }
        }
        if (StringUtils.isBlank(mainTitle) && StringUtils.isNotBlank(titleLabel)) {
            Metadata md = new Metadata(mainTitleType);
            md.setValue(titleLabel);
            newspaper.addMetadata(md);
        }

        if (StringUtils.isBlank(zdbIdAnalog) || StringUtils.isBlank(zdbIdDigital) || StringUtils.isBlank(identifier)) {
            problems.add("Export aborted, ZDB id or record id is missing");
            return false;
        }

        String sortNumber = null;
        String issueNumber = null;

        for (Metadata md : newspaperYear.getAllMetadata()) {
            // get current year

            if (md.getType().equals(sortNumberType)) {
                sortNumber = md.getValue();
            }
            if (md.getType().equals(issueNumberType)) {
                issueNumber = md.getValue();
            }
            if (language == null && md.getType().equals(languageType)) {
                language = md.getValue();
            }

            if (accessCondition == null && md.getType().equals(accessConditionType)) {
                accessCondition = md.getValue();
            }
        }

        if (StringUtils.isBlank(sortNumber) && StringUtils.isNotBlank(issueNumber) && StringUtils.isNumeric(issueNumber)) {
            try {
                Metadata md = new Metadata(sortNumberType);
                md.setValue(issueNumber);
                newspaperYear.addMetadata(md);
            } catch (UGHException e) {
                log.info(e);
            }
        }

        // check all issues
        for (DocStruct issue : issues) {

            // check if required metadata is available, otherwise add it
            String issueLabel = null;
            String issueTitle = null;
            String issueNo = null;
            String issueSortingNumber = null;
            String issueLanguage = null;
            String issueLicence = null;

            String issueIdentifier = null;
            String dateValue = null;
            String resource = null;
            String purl = null;

            for (Metadata md : issue.getAllMetadata()) {

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

                if (md.getType().equals(accessConditionType)) {
                    issueLicence = md.getValue();
                }

            }
            // create default metadata, if missing
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

            if (StringUtils.isBlank(issueLicence) && StringUtils.isNotBlank(accessCondition)) {
                Metadata md = new Metadata(accessConditionType);
                md.setValue(accessCondition);
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

            if (StringUtils.isBlank(dateValue)) {
                problems.add("Abort export, issue has no publication date");
                return false;
            }

            if (!dateValue.matches("\\d{4}-\\d{2}-\\d{2}")) {
                problems.add("Issue date " + dateValue + " has the wrong format. Expected is YYYY-MM-DD");
                return false;
            }

            // issue is valid, start export
            try {

                ExportFileformat issueExport = new MetsModsImportExport(prefs);

                DigitalDocument issueDigDoc = new DigitalDocument();
                issueExport.setDigitalDocument(issueDigDoc);

                setMetsParameter(process, projectSettings, goobiId, vp, issueExport);

                DocStruct newIssue = createDocstruct(issueType, issueDigDoc);
                copyMetadata("", issue, newIssue);
                copyMetadata("year", newspaperYear, newIssue);
                copyMetadata("newspaper", newspaper, newIssue);
                issueDigDoc.setLogicalDocStruct(newIssue);

                // create physSequence
                DocStruct physicalDocstruct = issueDigDoc.createDocStruct(oldPhysical.getType());
                issueDigDoc.setPhysicalDocStruct(physicalDocstruct);

                // add images
                if (issue.getAllToReferences() != null) {
                    for (Reference ref : issue.getAllToReferences()) {
                        DocStruct oldPage = ref.getTarget();
                        String filename = Paths.get(oldPage.getImageName()).getFileName().toString();

                        DocStruct newPage = createDocstruct(pageType, issueDigDoc);
                        copyMetadata("", oldPage, newPage);
                        if (newPage != null) {

                            newPage.setImageName(filename);
                            physicalDocstruct.addChild(newPage);

                            newIssue.addReferenceTo(newPage, "logical_physical");
                        }
                    }
                }

                // add supplements
                if (issue.getAllChildren() != null) {
                    for (DocStruct oldSupplement : issue.getAllChildren()) {
                        // create supplement, add it to new issue
                        DocStruct newSupplement = createDocstruct(oldSupplement.getType(), issueDigDoc);
                        newIssue.addChild(newSupplement);
                        // copy metadata
                        copyMetadata("", oldSupplement, newSupplement);
                        // create page references
                        if (oldSupplement.getAllToReferences() != null) {
                            for (Reference ref : oldSupplement.getAllToReferences()) {
                                DocStruct oldPage = ref.getTarget();
                                String filename = Paths.get(oldPage.getImageName()).getFileName().toString();
                                // find filename in new physSequence
                                for (DocStruct page : physicalDocstruct.getAllChildren()) {
                                    if (page.getImageName().equals(filename)) {
                                        newSupplement.addReferenceTo(page, "logical_physical");
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }

                // create filegroups
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
                                    VirtualFileGroup v = createFilegroup(vp, pfg, issueIdentifier);
                                    issueExport.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                                }
                            }
                        } else {
                            VirtualFileGroup v = createFilegroup(vp, pfg, issueIdentifier);
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

                // export files
                if (exportImages) {
                    String imagesFolder = process.getImagesTifDirectory(false);
                    String exportFolder = projectSettings.getString("/export/exportImageFolder")
                            .replace("$(meta.CatalogIDDigital)", issueIdentifier);
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
                            .replace("$(meta.CatalogIDDigital)", issueIdentifier);
                    List<DocStruct> pages = issueDigDoc.getPhysicalDocStruct().getAllChildren();
                    if (pages != null && !pages.isEmpty()) {
                        for (DocStruct page : pages) {
                            String filename = page.getImageName().substring(0, page.getImageName().indexOf(".")) + ".xml";
                            Path imageDestination =
                                    Paths.get(exportFolder, filename);
                            if (!StorageProvider.getInstance().isDirectory(imageDestination.getParent())) {
                                StorageProvider.getInstance().createDirectories(imageDestination.getParent());
                            }
                            StorageProvider.getInstance().copyFile(Paths.get(altoFolder, filename), imageDestination);
                        }
                    }
                }

            } catch (TypeNotAllowedAsChildException e) {
                log.error(e);
            }
        }

        // update/save generated data in goobi process
        process.writeMetadataFile(fileformat);

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

    private void setMetsParameter(Process process, SubnodeConfiguration projectSettings, String goobiId, VariableReplacer vp,
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

        fileFormat.setWriteLocal(false);
    }

    private DocStruct createDocstruct(DocStructType docstructType, DigitalDocument dd) {

        // create new docstruct
        DocStruct newDocstruct = null;
        try {
            newDocstruct = dd.createDocStruct(docstructType);
        } catch (TypeNotAllowedForParentException e1) {
            log.error(e1);
            return null;
        }
        return newDocstruct;
    }

    private void copyMetadata(String prefix, DocStruct oldDocstruct, DocStruct newDocstruct) {

        // copy metadata
        if (oldDocstruct.getAllMetadata() != null) {
            for (Metadata md : oldDocstruct.getAllMetadata()) {
                try {
                    // check if new field is allowed in prefs
                    MetadataType metadataType = prefs.getMetadataTypeByName(prefix + md.getType().getName());
                    if (metadataType != null) {
                        Metadata clone = new Metadata(metadataType);
                        clone.setValue(md.getValue());
                        clone.setAuthorityFile(md.getAuthorityID(), md.getAuthorityURI(), md.getAuthorityValue());
                        newDocstruct.addMetadata(clone);
                    }
                } catch (UGHException e) {
                    log.trace(e);
                }
            }
        }

        // copy persons
        if (oldDocstruct.getAllPersons() != null) {
            for (Person p : oldDocstruct.getAllPersons()) {
                try {
                    // check if new field is allowed in prefs
                    MetadataType metadataType = prefs.getMetadataTypeByName(prefix + p.getType().getName());
                    if (metadataType != null) {
                        Person clone = new Person(metadataType);
                        clone.setFirstname(p.getFirstname());
                        clone.setLastname(p.getLastname());
                        clone.setAuthorityFile(p.getAuthorityID(), p.getAuthorityURI(), p.getAuthorityValue());
                        newDocstruct.addPerson(clone);
                    }
                } catch (UGHException e) {
                    log.trace(e);
                }
            }
        }

        // copy corporates
        if (oldDocstruct.getAllCorporates() != null) {
            for (Corporate c : oldDocstruct.getAllCorporates()) {
                try {
                    // check if new field is allowed in prefs
                    MetadataType metadataType = prefs.getMetadataTypeByName(prefix + c.getType().getName());
                    if (metadataType != null) {
                        Corporate clone = new Corporate(metadataType);
                        clone.setMainName(c.getMainName());
                        clone.setPartName(c.getPartName());
                        clone.setSubNames(c.getSubNames());
                        clone.setAuthorityFile(c.getAuthorityID(), c.getAuthorityURI(), c.getAuthorityValue());
                        newDocstruct.addCorporate(clone);
                    }
                } catch (UGHException e) {
                    log.trace(e);
                }
            }
        }

        // copy groups
        if (oldDocstruct.getAllMetadataGroups() != null) {
            for (MetadataGroup mg : oldDocstruct.getAllMetadataGroups()) {
                try {
                    MetadataGroup newMetadataGroup = cloneMetadataGroup(prefix, mg);
                    newDocstruct.addMetadataGroup(newMetadataGroup);
                } catch (UGHException e) {
                    log.trace(e);
                }
            }
        }

    }

    private MetadataGroup cloneMetadataGroup(String prefix, MetadataGroup inGroup) throws MetadataTypeNotAllowedException {
        MetadataGroupType mgt = prefs.getMetadataGroupTypeByName(prefix + inGroup.getType().getName());
        MetadataGroup mg = new MetadataGroup(mgt);
        // copy metadata
        for (Metadata md : inGroup.getMetadataList()) {
            Metadata metadata = new Metadata(prefs.getMetadataTypeByName(md.getType().getName()));
            metadata.setValue(md.getValue());
            if (StringUtils.isNotBlank(md.getAuthorityValue())) {
                metadata.setAuthorityFile(md.getAuthorityID(), md.getAuthorityURI(), md.getAuthorityValue());
            }
            mg.addMetadata(metadata);
        }

        // copy persons
        for (Person p : inGroup.getPersonList()) {
            Person person = new Person(prefs.getMetadataTypeByName(p.getType().getName()));
            person.setFirstname(p.getFirstname());
            person.setLastname(p.getLastname());
            person.setAuthorityFile(p.getAuthorityID(), p.getAuthorityURI(), p.getAuthorityValue());
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
            Corporate corporate = new Corporate(prefs.getMetadataTypeByName(c.getType().getName()));
            corporate.setMainName(c.getMainName());
            if (c.getSubNames() != null) {
                for (NamePart subName : c.getSubNames()) {
                    corporate.addSubName(subName);
                }
            }
            corporate.setPartName(c.getPartName());
            if (c.getAuthorityID() != null && c.getAuthorityURI() != null && c.getAuthorityValue() != null) {
                corporate.setAuthorityFile(c.getAuthorityID(), c.getAuthorityURI(), c.getAuthorityValue());
            }
            mg.addCorporate(corporate);
        }

        // copy sub groups
        for (MetadataGroup subGroup : inGroup.getAllMetadataGroups()) {
            MetadataGroup copyOfSubGroup = cloneMetadataGroup(prefix, subGroup);
            mg.addMetadataGroup(copyOfSubGroup);
        }

        return mg;
    }

    private VirtualFileGroup createFilegroup(VariableReplacer variableRplacer, ProjectFileGroup projectFileGroup, String identifier) {
        VirtualFileGroup v = new VirtualFileGroup();
        v.setName(projectFileGroup.getName());
        v.setPathToFiles(variableRplacer.replace(projectFileGroup.getPath().replace("$(meta.CatalogIDDigital)", identifier)));
        v.setMimetype(projectFileGroup.getMimetype());
        v.setFileSuffix(projectFileGroup.getSuffix());
        v.setFileExtensionsToIgnore(projectFileGroup.getIgnoreMimetypes());
        v.setIgnoreConfiguredMimetypeAndSuffix(projectFileGroup.isUseOriginalFiles());
        if ("PRESENTATION".equals(projectFileGroup.getName())) {
            v.setMainGroup(true);
        }
        return v;
    }
}