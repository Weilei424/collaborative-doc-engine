package com.mwang.backend.repository;

import com.mwang.backend.domain.Document;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DocumentRepository extends PagingAndSortingRepository<Document, UUID>, CrudRepository<Document, UUID> {

}
