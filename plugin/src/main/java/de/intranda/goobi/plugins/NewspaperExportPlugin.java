package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.ProjectFileGroup;
import org.goobi.beans.Step;
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

    @Getter
    private String title = "intranda_export_newspaper";
    @Getter
    private PluginType type = PluginType.Export;
    @Getter
    @Setter
    private Step step;

    @Getter
    private List<String> problems;

    @Override
    public void setExportFulltext(boolean arg0) {
    }

    @Override
    public void setExportImages(boolean arg0) {
    }

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

        String goobiId = String.valueOf(process.getId());

        Prefs prefs = process.getRegelsatz().getPreferences();
        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        config.setExpressionEngine(new XPathExpressionEngine());





        MetadataType zdbIdType = prefs.getMetadataTypeByName(config.getString("/metadata/zdbid"));
        MetadataType identifierType = prefs.getMetadataTypeByName(config.getString("/metadata/identifier"));
        MetadataType issueDateType = prefs.getMetadataTypeByName(config.getString("/metadata/issueDate"));
        MetadataType yearDateType = prefs.getMetadataTypeByName(config.getString("/metadata/yearDate"));
        MetadataType labelType = prefs.getMetadataTypeByName("TitleDocMain");

        DocStructType newspaperType = prefs.getDocStrctTypeByName(config.getString("/docstruct/newspaper"));
        DocStructType yearType = prefs.getDocStrctTypeByName(config.getString("/docstruct/year"));
        DocStructType monthType = prefs.getDocStrctTypeByName(config.getString("/docstruct/month"));
        DocStructType dayType = prefs.getDocStrctTypeByName(config.getString("/docstruct/day"));
        DocStructType issueType = prefs.getDocStrctTypeByName(config.getString("/docstruct/issue"));

        DocStructType newspaperStubType = prefs.getDocStrctTypeByName(config.getString("/docstruct/newspaperStub"));


        // read fileformat
        Fileformat fileformat = process.readMetadataFile();
        DigitalDocument digitalDocument = fileformat.getDigitalDocument();
        DocStruct logical = digitalDocument.getLogicalDocStruct();
        DocStruct oldPhysical = digitalDocument.getPhysicalDocStruct();

        VariableReplacer vp = new VariableReplacer(digitalDocument, prefs, process, null);
        List<ProjectFileGroup> myFilegroups = process.getProjekt().getFilegroups();

        // check if it is a newspaper
        if (!logical.getType().isAnchor()) {
            // TODO return error
        }
        String zdbId = null;
        String identifier = null;
        String mainTitle= null;

        for (Metadata md : logical.getAllMetadata()) {
            //  get zdb id
            if (md.getType().equals(zdbIdType)) {
                zdbId = md.getValue();
            }
            //  get identifier
            else if (md.getType().equals(identifierType)) {
                identifier = md.getValue();
            } else if (md.getType().equals(labelType)) {
                mainTitle = md.getValue();
            }

        }
        if (StringUtils.isBlank(zdbId) || StringUtils.isBlank(identifier)) {
            // TODO return error
        }

        // list all issues
        DocStruct volume = logical.getAllChildren().get(0);

        String publicationYear = null;
        for (Metadata md : volume.getAllMetadata()) {

            // get current year
            if (md.getType().equals(yearDateType)) {
                publicationYear = md.getValue();
            }
        }

        List<DocStruct> issues = volume.getAllChildren();

        // create new anchor file for newspaper
        // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Gesamtaufnahme+Zeitung+1.0

        ExportFileformat newspaperExport = new MetsModsImportExport(prefs);

        DigitalDocument dd = new DigitalDocument();
        newspaperExport.setDigitalDocument(dd);
        newspaperExport.setGoobiID(goobiId);

        newspaperExport.setRightsOwner(vp.replace(process.getProjekt().getMetsRightsOwner()));
        newspaperExport.setRightsOwnerLogo(vp.replace(process.getProjekt().getMetsRightsOwnerLogo()));
        newspaperExport.setRightsOwnerSiteURL(vp.replace(process.getProjekt().getMetsRightsOwnerSite()));
        newspaperExport.setRightsOwnerContact(vp.replace(process.getProjekt().getMetsRightsOwnerMail()));
        newspaperExport.setDigiprovPresentation(vp.replace(process.getProjekt().getMetsDigiprovPresentation()));
        newspaperExport.setDigiprovReference(vp.replace(process.getProjekt().getMetsDigiprovReference()));
        newspaperExport.setDigiprovPresentationAnchor(vp.replace(process.getProjekt().getMetsDigiprovPresentationAnchor()));
        newspaperExport.setDigiprovReferenceAnchor(vp.replace(process.getProjekt().getMetsDigiprovReferenceAnchor()));

        newspaperExport.setMetsRightsLicense(vp.replace(process.getProjekt().getMetsRightsLicense()));
        newspaperExport.setMetsRightsSponsor(vp.replace(process.getProjekt().getMetsRightsSponsor()));
        newspaperExport.setMetsRightsSponsorLogo(vp.replace(process.getProjekt().getMetsRightsSponsorLogo()));
        newspaperExport.setMetsRightsSponsorSiteURL(vp.replace(process.getProjekt().getMetsRightsSponsorSiteURL()));

        newspaperExport.setPurlUrl(vp.replace(process.getProjekt().getMetsPurl()));
        newspaperExport.setContentIDs(vp.replace(process.getProjekt().getMetsContentIDs()));

        String pointer = process.getProjekt().getMetsPointerPath();
        pointer = vp.replace(pointer);
        newspaperExport.setMptrUrl(pointer);

        String anchor = process.getProjekt().getMetsPointerPathAnchor();
        pointer = vp.replace(anchor);
        newspaperExport.setMptrAnchorUrl(pointer);

        DocStruct newspaper = copyDocstruct(newspaperType, logical, dd);
        dd.setLogicalDocStruct(newspaper);

        // create volume for year
        // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Jahrgang+Zeitung+1.0
        DocStruct yearVolume = copyDocstruct(yearType, volume, dd);
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
            log.error(e);
        }

        for (DocStruct issue : issues) {
            // create issues, link issues to day
            // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Ausgabe+Zeitung+1.0
            // export each issue

            List<? extends Metadata> dates = issue.getAllMetadataByType(issueDateType);
            if (dates == null || dates.isEmpty()) {
                // TODO error
            }
            String dateValue = dates.get(0).getValue();
            if (!dateValue.matches("\\d{4}-\\d{2}-\\d{2}")) {
                System.out.println(dateValue);
                // TODO error
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
                    currentMonth = dd.createDocStruct(monthType);
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
                    currentDay = dd.createDocStruct(dayType);
                    currentDay.setOrderLabel(dateValue);
                    currentMonth.addChild(currentDay);
                } catch (TypeNotAllowedAsChildException e) {
                    log.error(e);
                }
            }
            try {
                DocStruct dummyIssue = dd.createDocStruct(issueType);
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
                String issueIdentifier = identifier + "_" + dateValue; // TODO use issuenumber?
                dummyIssue.setLink("https://example.org/viewer/metsresolver?id=" + issueIdentifier);


                ExportFileformat issueExport = new MetsModsImportExport(prefs);

                DigitalDocument issueDigDoc = new DigitalDocument();
                issueExport.setDigitalDocument(issueDigDoc);

                issueExport.setGoobiID(goobiId);

                issueExport.setRightsOwner(vp.replace(process.getProjekt().getMetsRightsOwner()));
                issueExport.setRightsOwnerLogo(vp.replace(process.getProjekt().getMetsRightsOwnerLogo()));
                issueExport.setRightsOwnerSiteURL(vp.replace(process.getProjekt().getMetsRightsOwnerSite()));
                issueExport.setRightsOwnerContact(vp.replace(process.getProjekt().getMetsRightsOwnerMail()));
                issueExport.setDigiprovPresentation(vp.replace(process.getProjekt().getMetsDigiprovPresentation()));
                issueExport.setDigiprovReference(vp.replace(process.getProjekt().getMetsDigiprovReference()));
                issueExport.setDigiprovPresentationAnchor(vp.replace(process.getProjekt().getMetsDigiprovPresentationAnchor()));
                issueExport.setDigiprovReferenceAnchor(vp.replace(process.getProjekt().getMetsDigiprovReferenceAnchor()));

                issueExport.setMetsRightsLicense(vp.replace(process.getProjekt().getMetsRightsLicense()));
                issueExport.setMetsRightsSponsor(vp.replace(process.getProjekt().getMetsRightsSponsor()));
                issueExport.setMetsRightsSponsorLogo(vp.replace(process.getProjekt().getMetsRightsSponsorLogo()));
                issueExport.setMetsRightsSponsorSiteURL(vp.replace(process.getProjekt().getMetsRightsSponsorSiteURL()));

                issueExport.setPurlUrl(vp.replace(process.getProjekt().getMetsPurl()));
                issueExport.setContentIDs(vp.replace(process.getProjekt().getMetsContentIDs()));
                issueExport.setMptrUrl(pointer);
                issueExport.setMptrAnchorUrl(pointer);

                issueExport.setWriteLocal(false);

                // create hierarchy for individual issue file

                // newspaper
                DocStruct dummyNewspaper = issueDigDoc.createDocStruct(newspaperStubType);
                dummyNewspaper.setLink("https://example.org/viewer/metsresolver?id=" + identifier);
                Metadata title = new Metadata(labelType);
                title.setValue(mainTitle);
                dummyNewspaper.addMetadata(title);
                // year
                DocStruct issueYear = issueDigDoc.createDocStruct(yearType);
                issueYear.setOrderLabel(dateValue.substring(0, 4));
                issueYear.setLink("https://example.org/viewer/metsresolver?id=" + yearIdentifier);
                title = new Metadata(labelType);
                title.setValue(yearTitle);
                issueYear.addMetadata(title);
                dummyNewspaper.addChild(issueYear);

                // month
                DocStruct   issueMonth = issueDigDoc.createDocStruct(monthType);
                issueMonth.setOrderLabel(monthValue);
                issueYear.addChild(issueMonth);
                // day
                DocStruct  issueDay = issueDigDoc.createDocStruct(dayType);
                issueDay.setOrderLabel(dateValue);
                issueMonth.addChild(issueDay);

                // issue
                DocStruct newIssue = copyDocstruct(issueType, issue, dd);
                issueDay.addChild(newIssue);

                // TODO check if identifier exist, otherwise add it
                // TODO check id ZDB IDs exist, otherwise add it

                issueDigDoc.setLogicalDocStruct(dummyNewspaper);

                // create physSequence
                DocStruct physicalDocstruct = issueDigDoc.createDocStruct(oldPhysical.getType());
                issueDigDoc.setPhysicalDocStruct(physicalDocstruct);

                // add images
                if (issue.getAllToReferences() != null) {
                    for (Reference ref : issue.getAllToReferences()) {
                        DocStruct oldPage = ref.getTarget();
                        String filename =Paths.get(oldPage.getImageName()).getFileName().toString();

                        DocStruct newPage = copyDocstruct(oldPage.getType(), oldPage, issueDigDoc);
                        newPage.setImageName(filename);
                        physicalDocstruct.addChild(newPage);
                        // export images + ocr
                    }
                }


                boolean useOriginalFiles = false;
                if (myFilegroups != null && myFilegroups.size() > 0) {
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
                                    VirtualFileGroup v = createFilegroup(vp, pfg);
                                    issueExport.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                                }
                            }
                        } else {
                            VirtualFileGroup v = createFilegroup(vp, pfg);
                            issueExport.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                        }
                    }
                }

                if (useOriginalFiles) {
                    // check if media folder contains images
                    // TODO only from sub group
                    List<Path> filesInFolder = StorageProvider.getInstance().listFiles(process.getImagesTifDirectory(false));
                    if (!filesInFolder.isEmpty()) {
                        // compare image names with files in mets file
                        List<DocStruct> pages = dd.getPhysicalDocStruct().getAllChildren();
                        if (pages != null && pages.size() > 0) {
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

                                    if (filenameInMets.toLowerCase().equals(imageName.toLowerCase())) {
                                        // found matching filename
                                        page.setImageName(imageNameInFolder.toString());
                                        break;
                                    }
                                }
                            }
                            // replace filename in mets file
                        }
                    }
                }

                issueExport.write("/tmp/" +issueIdentifier + ".xml");

            } catch (TypeNotAllowedAsChildException e) {
                log.error(e);
            }
        }
        newspaperExport.write("/tmp/" + process.getTitel() + ".xml");

        return true;
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


    private VirtualFileGroup createFilegroup(VariableReplacer variableRplacer, ProjectFileGroup projectFileGroup) {
        VirtualFileGroup v = new VirtualFileGroup();
        v.setName(projectFileGroup.getName());
        v.setPathToFiles(variableRplacer.replace(projectFileGroup.getPath()));
        v.setMimetype(projectFileGroup.getMimetype());
        v.setFileSuffix(projectFileGroup.getSuffix().trim());
        v.setFileExtensionsToIgnore(projectFileGroup.getIgnoreMimetypes());
        v.setIgnoreConfiguredMimetypeAndSuffix(projectFileGroup.isUseOriginalFiles());
        if (projectFileGroup.getName().equals("PRESENTATION")) {
            v.setMainGroup(true);
        }
        return v;
    }


}