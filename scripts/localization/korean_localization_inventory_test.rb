require "fileutils"
require "json"
require "minitest/autorun"
require "tmpdir"

require_relative "korean_localization_inventory"

class KoreanLocalizationInventoryTest < Minitest::Test
  def test_inventory_excludes_readme_operating_runtime_and_bilingual_manual_pairs
    with_repository do |root|
      write(root, "docs/guide.md", "# Guide\n\nEnglish only guide prose.\n")
      write(root, "README.md", "# README\n")
      write(root, "AGENTS.md", "# Agents\n")
      write(root, ".omx/artifact.md", "# Runtime\n")
      write(root, "docs/manual/en/index.md", "# English manual\n")
      write(root, "docs/manual/ko/index.md", "# Korean manual\n")
      write(root, "leader-core/src/main/kotlin/Sample.kt", "class Sample\n")

      inventory = KoreanLocalization::Inventory.new(root)

      assert_equal ["docs/guide.md"], inventory.documentation_candidates.map(&:path)
      assert_equal ["leader-core/src/main/kotlin/Sample.kt"], inventory.kotlin_candidates.map(&:path)
    end
  end

  def test_validator_detects_english_document_prose_but_ignores_code_fences
    with_repository do |root|
      write(root, "docs/code-only.md", <<~MD)
        # Example

        ```kotlin
        val lockName = "orders"
        val lease = Duration.ofSeconds(30)
        ```
      MD
      write(root, "docs/english.md", <<~MD)
        # Guide

        This document explains leader election behavior with substantial English
        prose. It describes ownership, lease extension, and operational
        boundaries in sentences that should be localized for Korean readers.
      MD
      write(root, "docs/korean.md", "# 가이드\n\n리더 선출 동작을 한국어로 설명합니다.\n")

      violations = KoreanLocalization::Validator.new(root).documentation_violations

      assert_equal ["docs/english.md"], violations.map(&:path)
    end
  end

  def test_validator_detects_english_kdoc_and_missing_contract_kdoc
    with_repository do |root|
      write(root, "leader-core/src/main/kotlin/Sample.kt", <<~KOTLIN)
        package sample

        /**
         * Explains a public model with English prose that must be localized.
         */
        data class EnglishModel(val name: String)

        data class MissingModel(val name: String)

        /**
         * 한국어로 설명한 모델입니다.
         */
        data class KoreanModel(val name: String)
      KOTLIN

      violations = KoreanLocalization::Validator.new(root).kdoc_violations

      assert violations.any? { |violation| violation.path == "leader-core/src/main/kotlin/Sample.kt" && violation.reason.include?("KDoc block") }
      assert violations.any? { |violation| violation.path == "leader-core/src/main/kotlin/Sample.kt" && violation.reason.include?("constructor lacks") }
    end
  end

  private

  def with_repository
    Dir.mktmpdir("korean-localization") do |root|
      yield root
    end
  end

  def write(root, path, content)
    absolute = File.join(root, path)
    FileUtils.mkdir_p(File.dirname(absolute))
    File.write(absolute, content)
  end
end
