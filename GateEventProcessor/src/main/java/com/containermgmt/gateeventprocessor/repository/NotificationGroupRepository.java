package com.containermgmt.gateeventprocessor.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads phone numbers of the employees belonging to a notification group.
 * ntf_notification_groups (group_code) -> ntf_groups_users -> emp_employees (phone_number).
 */
@Repository
@Slf4j
public class NotificationGroupRepository {

    private static final String PHONES_BY_GROUP_CODE = """
            SELECT DISTINCT e.phone_number
            FROM ntf_notification_groups g
            JOIN ntf_groups_users gu ON gu.id_notification_group = g.id_notification_group
            JOIN emp_employees e ON e.id_employee = gu.id_employee
            WHERE g.group_code = ?
              AND e.phone_number IS NOT NULL
              AND e.phone_number <> ''
            """;

    private final JdbcTemplate jdbc;

    public NotificationGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> findPhoneNumbersByGroupCode(String groupCode) {
        if (groupCode == null || groupCode.isBlank()) {
            return List.of();
        }
        try {
            return jdbc.query(PHONES_BY_GROUP_CODE,
                    (rs, rowNum) -> rs.getString("phone_number"),
                    groupCode);
        } catch (Exception e) {
            log.error("findPhoneNumbersByGroupCode query failed for group_code={}: {}",
                    groupCode, e.getMessage());
            return List.of();
        }
    }
}
