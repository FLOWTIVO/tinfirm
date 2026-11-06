package com.flowtivo.tinfirm.company.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CompanyRepository extends JpaRepository<CompanyEntity, UUID> {
}
