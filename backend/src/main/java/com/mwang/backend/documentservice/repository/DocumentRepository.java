package com.mwang.backend.documentservice.repository;

import com.mwang.backend.documentservice.domain.Document;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DocumentRepository extends PagingAndSortingRepository<Document, UUID>, CrudRepository<Document, UUID> {

}
