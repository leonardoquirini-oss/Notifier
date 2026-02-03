package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generic paginated result wrapper.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedResult<T> {

    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int currentPage;
}
