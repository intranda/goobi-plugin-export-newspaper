package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.sub.goobi.config.ConfigPlugins;
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
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataType;
import ugh.dl.NamePart;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
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
        MetadataType dateType = prefs.getMetadataTypeByName(config.getString("/metadata/date"));

        DocStructType newspaperType = prefs.getDocStrctTypeByName("/docstruct/newspaper");
        DocStructType yearType = prefs.getDocStrctTypeByName("/docstruct/year");
        DocStructType monthType = prefs.getDocStrctTypeByName("/docstruct/month");
        DocStructType dayType = prefs.getDocStrctTypeByName("/docstruct/day");
        DocStructType issueType = prefs.getDocStrctTypeByName("/docstruct/issue");

        // read fileformat
        Fileformat fileformat = process.readMetadataFile();
        DigitalDocument digitalDocument = fileformat.getDigitalDocument();
        DocStruct logical = digitalDocument.getLogicalDocStruct();
        DocStruct physical = digitalDocument.getPhysicalDocStruct();

        // check if it is a newspaper
        if (!logical.getType().isAnchor()) {
            // TODO return error
        }
        String zdbId = null;
        String identifier = null;

        for (Metadata md : logical.getAllMetadata()) {
            //  get zdb id
            if (md.getType().equals(zdbIdType)) {
                zdbId = md.getValue();
            }
            //  get identifier
            else if (md.getType().equals(identifierType)) {
                identifier = md.getValue();
            }
        }
        if (StringUtils.isBlank(zdbId) || StringUtils.isBlank(identifier)) {
            // TODO return error
        }

        // list all issues
        DocStruct volume = logical.getAllChildren().get(0);
        List<DocStruct> issues = volume.getAllChildren();

        // create new anchor file for newspaper
        // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Gesamtaufnahme+Zeitung+1.0

        Fileformat newspaperExport = new MetsModsImportExport(prefs);
        DigitalDocument dd = new DigitalDocument();
        newspaperExport.setDigitalDocument(dd);
        newspaperExport.setGoobiID(goobiId);
        DocStruct newspaper = copyDocstruct(newspaperType, logical, dd);
        dd.setLogicalDocStruct(newspaper);

        // create anchor file for year
        // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Jahrgang+Zeitung+1.0

        // for each issue, get normalized date, parse it
        // check if month and day exist or create months and days in year struct

        // create identifier if missing, add zdb id if missing

        // create issues, link issues to day
        // https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Ausgabe+Zeitung+1.0
        // export each issue

        // export year

        // export anchor

        return true;
    }

    private DocStruct copyDocstruct(DocStructType docstructType, DocStruct oldDocstruct, DigitalDocument dd)
            throws TypeNotAllowedForParentException, MetadataTypeNotAllowedException {

        // create new docstruct
        DocStruct newDocstruct = dd.createDocStruct(docstructType);

        // copy metadata
        if (oldDocstruct.getAllMetadata() != null) {
            for (Metadata md : oldDocstruct.getAllMetadata()) {
                Metadata clone = new Metadata(md.getType());
                clone.setValue(md.getValue());
                clone.setAutorityFile(md.getAuthorityID(), md.getAuthorityURI(), md.getAuthorityValue());
                newDocstruct.addMetadata(clone);
            }
        }

        // copy persons
        if (oldDocstruct.getAllPersons() != null) {
            for (Person p : oldDocstruct.getAllPersons()) {
                Person clone = new Person(p.getType());
                clone.setFirstname(p.getFirstname());
                clone.setLastname(p.getLastname());
                clone.setAutorityFile(p.getAuthorityID(), p.getAuthorityURI(), p.getAuthorityValue());
                newDocstruct.addPerson(clone);
            }
        }

        // copy corporates
        if (oldDocstruct.getAllCorporates() != null) {
            for (Corporate c : oldDocstruct.getAllCorporates()) {
                Corporate clone = new Corporate(c.getType());
                clone.setMainName(c.getMainName());
                ;
                clone.setPartName(c.getPartName());
                clone.setSubNames(c.getSubNames());
                clone.setAutorityFile(c.getAuthorityID(), c.getAuthorityURI(), c.getAuthorityValue());
                newDocstruct.addCorporate(clone);
            }
        }

        // copy groups
        if (oldDocstruct.getAllMetadataGroups() != null) {
            for (MetadataGroup mg : oldDocstruct.getAllMetadataGroups()) {
                MetadataGroup newMetadataGroup = cloneMetadataGroup(mg);
                newDocstruct.addMetadataGroup(newMetadataGroup);
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

}