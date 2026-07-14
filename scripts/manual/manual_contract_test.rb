require "fileutils"
require "minitest/autorun"
require "tmpdir"
require "yaml"

require_relative "manual_contract"

class ManualContractTest < Minitest::Test
  RELEASE = { "ref" => "0.4.0", "commit" => "17ab7f872c1f96318c73d3580729cac20a67e017" }.freeze
  INVENTORY = [{ "gradlePath" => ":bluetape4k-leader-core", "sourceDir" => "leader-core" }].freeze

  def test_reports_the_required_contract_errors_exactly
    with_repository do |root, manifest|
      duplicate = Marshal.load(Marshal.dump(manifest["modules"].first))
      manifest["modules"] << duplicate
      write_manifest(root, manifest)
      FileUtils.rm(File.join(root, "docs/manual/ko/modules/bluetape4k-leader-core.md"))
      english = File.join(root, "docs/manual/en/modules/bluetape4k-leader-core.md")
      File.write(english, File.read(english).sub(/^## sources \{#sources\}.*\z/m, ""))

      errors = validator(root).errors

      assert_includes errors, "bluetape4k-leader-core: duplicate id"
      assert_includes errors, "bluetape4k-leader-core: missing Korean document"
      assert_includes errors, "bluetape4k-leader-core: English document missing required section sources"
    end
  end

  def test_reports_inventory_paths_assets_and_release_drift_exactly
    with_repository do |root, manifest|
      entry = manifest["modules"].first
      entry["sourcePaths"] = ["../outside"]
      entry["assets"] = ["assets/core/model.svg", "assets/core/model.png"]
      write_manifest(root, manifest)
      FileUtils.mkdir_p(File.join(root, "docs/manual/assets/core"))
      File.write(File.join(root, "docs/manual/assets/core/model.svg"), "<svg/>\n")
      File.write(File.join(root, "docs/manual/assets/core/orphan.svg"), "<svg/>\n")

      errors = validator(root, inventory: INVENTORY + [{ "gradlePath" => ":missing", "sourceDir" => "missing" }]).errors

      assert_includes errors, "missing: missing from manifest"
      assert_includes errors, "bluetape4k-leader-core: unsafe sourcePaths path ../outside"
      assert_includes errors, "bluetape4k-leader-core: missing paired asset assets/core/model.png"
      assert_includes errors, "manual assets: orphan asset assets/core/orphan.svg"
    end
  end

  def test_rejects_absolute_and_symlink_escape_paths
    with_repository do |root, manifest|
      Dir.mktmpdir("outside-manual") do |outside|
        File.symlink(outside, File.join(root, "escape"))
        manifest["modules"].first["sourcePaths"] = ["/absolute", "escape"]
        write_manifest(root, manifest)
        errors = validator(root).errors
        assert_includes errors, "bluetape4k-leader-core: unsafe sourcePaths path /absolute"
        assert_includes errors, "bluetape4k-leader-core: unsafe sourcePaths path escape"
      end
    end
  end

  def test_rejects_wrong_release_metadata
    with_repository do |root, manifest|
      manifest["releaseCommit"] = "b" * 40
      write_manifest(root, manifest)
      assert_includes validator(root).errors,
                      "manual manifest releaseCommit must be #{RELEASE.fetch('commit')}"
    end
  end

  def test_validates_bilingual_chapters_and_their_frontmatter
    with_repository do |root, manifest|
      manifest["modules"].first["chapters"] = [{
        "id" => "transactions", "en" => "en/modules/core/transactions.md", "ko" => "ko/modules/core/transactions.md",
      }]
      write_manifest(root, manifest)
      FileUtils.mkdir_p(File.join(root, "docs/manual/en/modules/core"))
      File.write(
        File.join(root, "docs/manual/en/modules/core/transactions.md"),
        "---\nmanualId: wrong\nchapterId: wrong\n---\n",
      )

      errors = validator(root).errors

      assert_includes errors, "bluetape4k-leader-core/transactions: English manualId must be bluetape4k-leader-core"
      assert_includes errors, "bluetape4k-leader-core/transactions: English chapterId must be transactions"
      assert_includes errors, "bluetape4k-leader-core/transactions: missing Korean document"
    end
  end

  def test_rejects_missing_and_escaping_markdown_references
    with_repository do |root, _manifest|
      english = File.join(root, "docs/manual/en/modules/bluetape4k-leader-core.md")
      File.write(english, File.read(english) + "\n[Missing](missing.md)\n[Escape](../../../../../outside.md)\n")

      errors = validator(root).errors

      assert_includes errors, "bluetape4k-leader-core: missing Markdown reference missing.md"
      assert_includes errors, "bluetape4k-leader-core: unsafe Markdown reference ../../../../../outside.md"
    end
  end

  def test_validates_top_level_overview_documents_and_paired_assets
    with_repository do |root, manifest|
      manifest["overview"] = {
        "documents" => {
          "en" => ["en/index.md"],
          "ko" => ["ko/index.md"],
        },
        "assets" => ["assets/overview/map.svg"],
      }
      FileUtils.mkdir_p(File.join(root, "docs/manual/en"))
      File.write(File.join(root, "docs/manual/en/index.md"), "# Overview\n")
      write_manifest(root, manifest)

      errors = validator(root).errors

      assert_includes errors, "manual overview: missing Korean document ko/index.md"
      assert_includes errors, "manual overview: missing asset assets/overview/map.svg"
      assert_includes errors, "manual overview: missing paired asset assets/overview/map.png"
    end
  end

  def test_rejects_overview_locale_inventory_drift
    with_repository do |root, manifest|
      manifest["overview"] = {
        "documents" => {
          "en" => ["en/index.md"],
          "ko" => ["ko/getting-started.md"],
        },
        "assets" => [],
      }
      %w[en/index.md ko/getting-started.md].each do |path|
        absolute = File.join(root, "docs/manual", path)
        FileUtils.mkdir_p(File.dirname(absolute))
        File.write(absolute, "# Overview\n")
      end
      write_manifest(root, manifest)

      assert_includes validator(root).errors, "manual overview: English/Korean document inventory differs"
    end
  end

  def test_rejects_locale_chapter_and_asset_symlinks_that_escape_the_repository
    with_repository do |root, manifest|
      Dir.mktmpdir("outside-manual-contract") do |outside|
        external_document = File.join(outside, "manual.md")
        external_asset = File.join(outside, "model.svg")
        File.write(external_document, complete_document("bluetape4k-leader-core"))
        File.write(external_asset, "<svg/>\n")

        english = File.join(root, "docs/manual/en/modules/bluetape4k-leader-core.md")
        FileUtils.rm(english)
        File.symlink(external_document, english)

        chapter_dir = File.join(root, "docs/manual/en/modules/core")
        FileUtils.mkdir_p(chapter_dir)
        File.symlink(external_document, File.join(chapter_dir, "transactions.md"))
        manifest["modules"].first["chapters"] = [{
          "id" => "transactions", "en" => "en/modules/core/transactions.md", "ko" => "ko/modules/core/transactions.md",
        }]

        asset_dir = File.join(root, "docs/manual/assets/core")
        FileUtils.mkdir_p(asset_dir)
        File.symlink(external_asset, File.join(asset_dir, "model.svg"))
        File.write(File.join(asset_dir, "model.png"), "png\n")
        manifest["modules"].first["assets"] = ["assets/core/model.svg", "assets/core/model.png"]
        write_manifest(root, manifest)

        errors = validator(root).errors

        assert_includes errors, "bluetape4k-leader-core: unsafe English document path"
        assert_includes errors, "bluetape4k-leader-core/transactions: unsafe English document path"
        assert_includes errors, "bluetape4k-leader-core: unsafe asset path assets/core/model.svg"
      end
    end
  end

  private

  def with_repository
    Dir.mktmpdir("manual-contract") do |root|
      %w[docs/manual/en/modules docs/manual/ko/modules leader-core/src/main/kotlin].each do |path|
        FileUtils.mkdir_p(File.join(root, path))
      end
      manifest = {
        "schemaVersion" => 2,
        "repository" => "bluetape4k/bluetape4k-leader",
        "releaseRef" => RELEASE.fetch("ref"),
        "releaseCommit" => RELEASE.fetch("commit"),
        "modules" => [{
          "id" => "bluetape4k-leader-core",
          "gradlePath" => ":bluetape4k-leader-core",
          "sourceDir" => "leader-core",
          "kind" => "library",
          "group" => "foundation",
          "artifact" => "io.github.bluetape4k.leader:bluetape4k-leader-core",
          "en" => "en/modules/bluetape4k-leader-core.md",
          "ko" => "ko/modules/bluetape4k-leader-core.md",
          "sourcePaths" => ["leader-core/src/main/kotlin"],
          "testPaths" => [],
          "workshops" => [],
        }],
      }
      write_manifest(root, manifest)
      %w[en ko].each do |locale|
        File.write(
          File.join(root, "docs/manual/#{locale}/modules/bluetape4k-leader-core.md"),
          complete_document("bluetape4k-leader-core"),
        )
      end
      yield root, manifest
    end
  end

  def complete_document(id)
    frontmatter = "---\nmanualId: #{id}\n---\n\n"
    frontmatter + ManualDocs::REQUIRED_SECTIONS.map { |section| "## #{section} {##{section}}\n\nText.\n" }.join("\n")
  end

  def write_manifest(root, manifest)
    File.write(File.join(root, "docs/manual/manifest.yaml"), YAML.dump(manifest))
  end

  def validator(root, inventory: INVENTORY)
    ManualDocs::Validator.new(
      inventory: inventory,
      manifest_path: File.join(root, "docs/manual/manifest.yaml"),
      repository_root: root,
      expected_release: RELEASE,
    )
  end
end
