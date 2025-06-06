package com.mwang.backend.repositories;

import com.mwang.backend.domain.Document;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DocumentRepository extends PagingAndSortingRepository<Document, UUID>, CrudRepository<Document, UUID> {

}
