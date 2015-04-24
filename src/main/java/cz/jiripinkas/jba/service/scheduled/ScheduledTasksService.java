package cz.jiripinkas.jba.service.scheduled;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import cz.jiripinkas.jba.dto.ItemDto;
import cz.jiripinkas.jba.entity.Blog;
import cz.jiripinkas.jba.entity.Category;
import cz.jiripinkas.jba.entity.Configuration;
import cz.jiripinkas.jba.entity.Item;
import cz.jiripinkas.jba.entity.NewsItem;
import cz.jiripinkas.jba.repository.BlogRepository;
import cz.jiripinkas.jba.repository.ItemRepository;
import cz.jiripinkas.jba.repository.NewsItemRepository;
import cz.jiripinkas.jba.service.AllCategoriesService;
import cz.jiripinkas.jba.service.BlogService;
import cz.jiripinkas.jba.service.CategoryService;
import cz.jiripinkas.jba.service.ConfigurationService;
import cz.jiripinkas.jba.service.ItemService;
import cz.jiripinkas.jba.service.ItemService.MaxType;
import cz.jiripinkas.jba.service.ItemService.OrderType;
import cz.jiripinkas.jba.service.NewsService;

@Service
public class ScheduledTasksService {

	@Autowired
	private BlogRepository blogRepository;

	@Autowired
	private BlogService blogService;

	@Autowired
	private ItemService itemService;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private NewsItemRepository newsItemRepository;

	@Autowired
	private NewsService newsService;

	@Autowired
	private ConfigurationService configurationService;

	@Autowired
	private CategoryService categoryService;

	@Autowired
	private AllCategoriesService allCategoriesService;

	/**
	 * For each blog retrieve latest items and store them into database.
	 */
	// 1 hour = 60 seconds * 60 minutes * 1000
	@Scheduled(fixedDelay = 60 * 60 * 1000)
	@CacheEvict(value = "itemCount", allEntries = true)
	public void reloadBlogs() {
		// first process blogs which have aggregator = null,
		// next blogs with aggregator = false
		// and last blogs with aggregator = true
		List<Blog> blogs = blogRepository.findAll(new Sort(Direction.ASC, "aggregator"));
		List<String> allLinks = itemRepository.findAllLinks();
		List<String> allLowercaseTitles = itemRepository.findAllLowercaseTitles();
		Map<String, Object> allLinksMap = new HashMap<String, Object>();
		for (String link : allLinks) {
			allLinksMap.put(link, null);
		}
		Map<String, Object> allLowercaseTitlesMap = new HashMap<String, Object>();
		for (String title : allLowercaseTitles) {
			allLowercaseTitlesMap.put(title, null);
		}
		for (Blog blog : blogs) {
			blogService.saveItems(blog, allLinksMap, allLowercaseTitlesMap);
		}
		blogService.setLastIndexedDateFinish(new Date());
	}

	/**
	 * Remove too old items without any clicks ... nobody will see them anyway.
	 */
	// one day = 60 * 60 * 24 * 1000
	@Scheduled(initialDelay = 60 * 60 * 12 * 1000, fixedDelay = 60 * 60 * 24 * 1000)
	@CacheEvict(value = "itemCount", allEntries = true)
	public void cleanOldItems() {
		List<Item> items = itemRepository.findAll();
		for (Item item : items) {
			if (item.getClickCount() == 0 && itemService.isTooOld(item.getPublishedDate())) {
				itemRepository.delete(item);
			}
		}
	}

	int[] getPreviousWeekAndYear(Date date) throws ParseException {
		Calendar calendar = new GregorianCalendar();
		calendar.setTime(date);
		int week = calendar.get(Calendar.WEEK_OF_YEAR);
		int year = calendar.get(Calendar.YEAR);
		if (calendar.get(Calendar.WEEK_OF_YEAR) > 1) {
			week = week - 1;
		} else {
			year = year - 1;
			Calendar c = Calendar.getInstance();
			c.setMinimalDaysInFirstWeek(7);
			DateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
			c.setTime(sdf.parse("31/12/" + year));
			week = c.get(Calendar.WEEK_OF_YEAR);
		}
		return new int[] { week, year };
	}

	/**
	 * Generate best of weekly news
	 */
	@Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 2000)
	public void addWeeklyNews() throws ParseException {
		final int[] weekAndYear = getPreviousWeekAndYear(new Date());
		final int week = weekAndYear[0];
		final int year = weekAndYear[1];
		String currentWeekShortTitle = "best-of-" + week + "-" + year;
		NewsItem newsItem = newsItemRepository.findByShortName(currentWeekShortTitle);
		if (newsItem == null) {
			newsItem = new NewsItem();
			Configuration configuration = configurationService.find();
			newsItem.setTitle(configuration.getChannelTitle() + " Weekly: Best of " + week + "/" + year);
			newsItem.setShortName(currentWeekShortTitle);
			newsItem.setShortDescription("Best of " + configuration.getChannelTitle() + ", year " + year + ", week " + week);
			String description = "<p>" + configuration.getChannelTitle() + " brings you interesting news every day.";
			description += " Each week I select the best of:</p>";
			List<Category> categories = categoryService.findAll();
			for (Category category : categories) {
				description += "<table class='table'>";
				description += "<tr>";
				description += "<td>";
				description += "<h4>" + category.getName() + "</h4>";
				description += "</td>";
				description += "</tr>";
				List<ItemDto> dtoItems = itemService.getDtoItems(0, false, OrderType.MOST_VIEWED, MaxType.WEEK, new Integer[] { category.getId() });
				for (int i = 0; i < dtoItems.size() && i < 5; i++) {
					ItemDto itemDto = dtoItems.get(i);
					description += "<tr>";
					description += "<td>";
					description += "<a href='" + itemDto.getLink() + "' target='_blank'>";
					description += "<img src='/spring/icon/" + itemDto.getBlog().getId() + "' style='float:left;padding-right:5px;height:30px' />";
					description += itemDto.getTitle();
					description += "</a>";
					description += "</td>";
					description += "</tr>";
				}
				description += "</table>";
			}
			newsItem.setDescription(description);
			newsService.save(newsItem);
		}
	}

	private static class TwitterRetweetJson {

		private int count;

		public int getCount() {
			return count;
		}

		@SuppressWarnings("unused")
		public void setCount(int count) {
			this.count = count;
		}
	}

	private static class FacebookShareJson {

		private int shares;

		public int getShares() {
			return shares;
		}

		@SuppressWarnings("unused")
		public void setShares(int shares) {
			this.shares = shares;
		}
	}

	private static class LinkedinShareJson {

		private int count;

		public int getCount() {
			return count;
		}

		@SuppressWarnings("unused")
		public void setCount(int count) {
			this.count = count;
		}
	}

	@Autowired
	private RestTemplate restTemplate;

	@Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 1000)
	public void retrieveSocialShareCount() {
		System.out.println("retrieve social share count start");
		Integer[] allCategories = allCategoriesService.getAllCategoryIds();
		int page = 0;
		int retrievedItems = 0;
		do {
			List<ItemDto> dtoItems = itemService.getDtoItems(page++, false, OrderType.LATEST, MaxType.WEEK, allCategories);
			retrievedItems = dtoItems.size();
			for (ItemDto itemDto : dtoItems) {
				try {
					TwitterRetweetJson twitterRetweetJson = restTemplate.getForObject("https://cdn.api.twitter.com/1/urls/count.json?url=" + itemDto.getLink(), TwitterRetweetJson.class);
					if (twitterRetweetJson.getCount() != itemDto.getTwitterRetweetCount()) {
						itemRepository.setTwitterRetweetCount(itemDto.getId(), twitterRetweetJson.getCount());
						// System.out.println("URL: " + itemDto.getLink() +
						// " has twitter retweet count: " +
						// twitterRetweetJson.getCount());
					}
				} catch (Exception ex) {
					ex.printStackTrace();
				}
				try {
					FacebookShareJson facebookShareJson = restTemplate.getForObject("http://graph.facebook.com/?id=" + itemDto.getLink(), FacebookShareJson.class);
					if (facebookShareJson.getShares() != itemDto.getFacebookShareCount()) {
						itemRepository.setFacebookShareCount(itemDto.getId(), facebookShareJson.getShares());
						// System.out.println("URL: " + itemDto.getLink() +
						// " has facebook retweet count: " +
						// facebookShareJson.getShares());
					}
				} catch (Exception ex) {
					ex.printStackTrace();
				}
				try {
					LinkedinShareJson linkedinShareJson = restTemplate.getForObject("https://www.linkedin.com/countserv/count/share?format=json&url=" + itemDto.getLink(), LinkedinShareJson.class);
					if (linkedinShareJson.getCount() != itemDto.getLinkedinShareCount()) {
						itemRepository.setLinkedinShareCount(itemDto.getId(), linkedinShareJson.getCount());
						// System.out.println("URL: " + itemDto.getLink() +
						// " has linkedin retweet count: " +
						// linkedinShareJson.getCount());
					}
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		} while (retrievedItems > 0);
		System.out.println("retrieve social share count finish");
	}

}
