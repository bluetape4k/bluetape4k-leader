# frozen_string_literal: true

require "fileutils"
require "minitest/autorun"
require "pathname"
require "tmpdir"
require "yaml"
require_relative "readme_jvm25_contract"

class ReadmeJvm25ContractTest < Minitest::Test
  RELEASE_REF = "0.5.0"
  RELEASE_COMMIT = "a" * 40

  def test_accepts_aligned_readmes_diagram_pair_and_pinned_manual_pages
    with_repository do |root|
      assert_empty ReadmeJvm25Contract.errors(root: root)
    end
  end

  def test_reports_stale_diagram_and_locale_requirement_drift
    with_repository do |root|
      svg = root.join(ReadmeJvm25Contract::OVERVIEW_SVG)
      svg.write(svg.read.sub(">JVM 25+<", ">JVM 21<"))
      readme = root.join("README.ko.md")
      readme.write(readme.read.sub("JVM-25-", "JVM-21-").sub("JVM 25+", "JVM 21+"))

      errors = ReadmeJvm25Contract.errors(root: root)

      assert_includes errors, "overview SVG must label the virtual-thread chip as JVM 25+"
      assert_includes errors, "overview SVG still contains stale JVM 21 text"
      assert_includes errors, "README.ko.md must advertise JVM 25 in the badge"
      assert_includes errors, "README.ko.md must require JVM 25+"
    end
  end

  def test_reports_manual_pages_without_pinned_release_boundary
    with_repository do |root|
      page = root.join(ReadmeJvm25Contract::MANUAL_PAGES.first)
      page.write(page.read.gsub(RELEASE_COMMIT, "b" * 40))

      errors = ReadmeJvm25Contract.errors(root: root)

      assert_includes errors, "#{ReadmeJvm25Contract::MANUAL_PAGES.first} must pin the overview PNG to releaseCommit"
      assert_includes errors, "#{ReadmeJvm25Contract::MANUAL_PAGES.first} must pin the overview SVG to releaseCommit"
    end
  end

  private

  def with_repository
    Dir.mktmpdir("readme-jvm25-contract") do |directory|
      root = Pathname.new(directory)
      write(root.join(ReadmeJvm25Contract::OVERVIEW_SVG), <<~SVG)
        <svg><text>JVM 25+</text></svg>
      SVG
      write_png(root.join(ReadmeJvm25Contract::OVERVIEW_PNG))
      ReadmeJvm25Contract::README_FILES.each do |relative|
        write(
          root.join(relative),
          "[![JVM](https://img.shields.io/badge/JVM-25-ED8B00)]\nJVM 25+\n#{ReadmeJvm25Contract::OVERVIEW_REFERENCE}\n",
        )
      end
      write(
        root.join("docs/manual/manifest.yaml"),
        YAML.dump("releaseRef" => RELEASE_REF, "releaseCommit" => RELEASE_COMMIT),
      )
      ReadmeJvm25Contract::MANUAL_PAGES.each do |relative|
        write(
          root.join(relative),
          "`#{RELEASE_REF}` release; not later Snapshot changes.\n" \
          "https://raw.example/#{RELEASE_COMMIT}/#{ReadmeJvm25Contract::OVERVIEW_PNG}\n" \
          "https://example/#{RELEASE_COMMIT}/#{ReadmeJvm25Contract::OVERVIEW_SVG}\n",
        )
      end

      yield root
    end
  end

  def write(path, content)
    FileUtils.mkdir_p(path.dirname)
    path.write(content)
  end

  def write_png(path)
    header = "\x89PNG\r\n\x1A\n".b + [13].pack("N") + "IHDR" + [2800, 1800, 8, 2, 0, 0, 0].pack("NNC5")
    FileUtils.mkdir_p(path.dirname)
    path.binwrite(header)
  end
end
