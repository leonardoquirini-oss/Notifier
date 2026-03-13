package com.containermgmt.tfpeventingester.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

import java.util.List;
import java.util.stream.Collectors;

@Table("evt_event_attachments")
@IdName("id_event_attachment")
@IdGenerator("nextval('s_evt_event_attachments')")
public class EvtEventAttachment extends Model {

    public static List<Long> findIdDocumentsByUnitEventId(Long idUnitEvent) {
        return find("id_unit_event = ? AND id_document IS NOT NULL", idUnitEvent)
                .stream()
                .map(m -> ((Number) m.get("id_document")).longValue())
                .collect(Collectors.toList());
    }

    public static int deleteByUnitEventId(Long idUnitEvent) {
        return delete("id_unit_event = ?", idUnitEvent);
    }
}
