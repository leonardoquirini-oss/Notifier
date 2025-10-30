package com.containermgmt.notifier.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

/**
 * Model per la tabella template_email_lists
 */
@Table("template_email_lists")
@IdName("id_association")
@IdGenerator("nextval('template_email_lists_id_association_seq')")
public class TemplateEmailList extends Model {
    // Nessuna validazione particolare necessaria
}
