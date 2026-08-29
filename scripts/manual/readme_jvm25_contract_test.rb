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
  BASE_VERSION = "1.0.0"

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

  def test_reports_readme_release_boundary_drift
    with_repository do |root|
      readme = root.join("README.md")
      readme.write(
        readme.read
          .sub("Current stable version: `#{RELEASE_REF}`", "Current stable version: `0.4.0`")
          .sub("Leader #{RELEASE_REF} manual", "Leader 0.4 manual")
          .sub("Current development line: `#{BASE_VERSION}-SNAPSHOT`", "Current development line: `0.6.0-SNAPSHOT`")
          .sub("`#{BASE_VERSION}+` development line", "`0.6.0+` development line"),
      )
      readme_ko = root.join("README.ko.md")
      readme_ko.write(
        readme_ko.read
          .sub("현재 안정 버전: `#{RELEASE_REF}`", "현재 안정 버전: `0.4.0`")
          .sub("Leader #{RELEASE_REF} 매뉴얼", "Leader 0.4 매뉴얼")
          .sub("현재 개발 버전: `#{BASE_VERSION}-SNAPSHOT`", "현재 개발 버전: `0.6.0-SNAPSHOT`")
          .sub("`#{BASE_VERSION}+` 개발선", "`0.6.0+` 개발선"),
      )

      errors = ReadmeJvm25Contract.errors(root: root)

      assert_includes errors, "README.md must advertise stable version #{RELEASE_REF}"
      assert_includes errors, "README.md must link the Leader #{RELEASE_REF} manual"
      assert_includes errors, "README.md must identify development line #{BASE_VERSION}-SNAPSHOT"
      assert_includes errors, "README.md must describe development APIs as #{BASE_VERSION}+"
      assert_includes errors, "README.ko.md must advertise stable version #{RELEASE_REF}"
      assert_includes errors, "README.ko.md must link the Leader #{RELEASE_REF} manual"
      assert_includes errors, "README.ko.md must identify development line #{BASE_VERSION}-SNAPSHOT"
      assert_includes errors, "README.ko.md must describe development APIs as #{BASE_VERSION}+"
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
        release_boundary = if relative == "README.md"
                             "Current stable version: `#{RELEASE_REF}`\n" \
                               "Current development line: `#{BASE_VERSION}-SNAPSHOT`\n" \
                               "[Leader #{RELEASE_REF} manual](docs/manual/en/index.md)\n" \
                               "Development APIs use the `#{BASE_VERSION}+` development line.\n"
                           else
                             "현재 안정 버전: `#{RELEASE_REF}`\n" \
                               "현재 개발 버전: `#{BASE_VERSION}-SNAPSHOT`\n" \
                               "[Leader #{RELEASE_REF} 매뉴얼](docs/manual/ko/index.md)\n" \
                               "개발 API는 `#{BASE_VERSION}+` 개발선에서 제공합니다.\n"
                           end
        write(
          root.join(relative),
          "[![JVM](https://img.shields.io/badge/JVM-25-ED8B00)]\nJVM 25+\n" \
            "#{ReadmeJvm25Contract::OVERVIEW_REFERENCE}\n#{release_boundary}",
        )
      end
      write(root.join("gradle.properties"), "baseVersion=#{BASE_VERSION}\n")
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
