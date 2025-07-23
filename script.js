document.addEventListener('DOMContentLoaded', () => {
    const activityItems = document.querySelectorAll('.activity-item');

    activityItems.forEach(item => {
        const activityName = item.querySelector('.activity-name');
        const activityDetails = item.querySelector('.activity-details');
        const mapContainer = item.querySelector('.map-container');
        const imageCarousel = item.querySelector('.image-carousel');
        const location = item.dataset.location;
        const images = JSON.parse(item.dataset.images || '[]'); // Parse image URLs from data-images

        activityName.addEventListener('click', () => {
            const isOpen = item.classList.contains('open');

            // Close all other open activities
            document.querySelectorAll('.activity-item.open').forEach(openItem => {
                if (openItem !== item) {
                    openItem.classList.remove('open');
                    openItem.querySelector('.activity-details').classList.remove('expanded');
                    // Clear map and images from closed items
                    openItem.querySelector('.map-container').innerHTML = '';
                    openItem.querySelector('.image-carousel').innerHTML = '';
                    if (openItem.querySelector('.carousel-nav')) {
                        openItem.querySelector('.carousel-nav').remove();
                    }
                }
            });

            // Toggle current activity
            item.classList.toggle('open');
            activityDetails.classList.toggle('expanded');

            if (item.classList.contains('open')) {
                // Load map
                loadMap(mapContainer, location);
                // Load images
                loadImageCarousel(imageCarousel, images);
            } else {
                // Clear map and images when closing
                mapContainer.innerHTML = '';
                imageCarousel.innerHTML = '';
                if (item.querySelector('.carousel-nav')) {
                    item.querySelector('.carousel-nav').remove();
                }
            }
        });
    });

    function loadMap(container, location) {
        // Using Google Maps Embed API for simplicity.
        // For interactive map via JS API, you'd need the Google Maps JS API loaded
        // with your API key, and then use new google.maps.Map() etc.
        // This solution opens map in a new tab for interaction.
        // A direct iframe embed for a static map is possible but less interactive.
        const encodedLocation = encodeURIComponent(location);
        container.innerHTML = `<iframe
            width="100%"
            height="100%"
            frameborder="0" style="border:0"
            src="https://www.google.com/maps/embed/v1/place?key=YOUR_Maps_API_KEY&q=${encodedLocation}"
            allowfullscreen>
        </iframe>`;
    }

    function loadImageCarousel(container, imageUrls) {
        container.innerHTML = ''; // Clear previous images
        if (imageUrls.length === 0) {
            container.innerHTML = '<p>No images available.</p>';
            return;
        }

        imageUrls.forEach(url => {
            const imgWrapper = document.createElement('div');
            imgWrapper.classList.add('carousel-image-wrapper');
            const img = document.createElement('img');
            img.src = url;
            img.alt = 'Venue image';
            imgWrapper.appendChild(img);
            container.appendChild(imgWrapper);
        });

        // Add carousel navigation if more than one image
        if (imageUrls.length > 1) {
            const navContainer = document.createElement('div');
            navContainer.classList.add('carousel-nav');
            const prevButton = document.createElement('button');
            prevButton.textContent = 'Previous';
            const nextButton = document.createElement('button');
            nextButton.textContent = 'Next';

            prevButton.addEventListener('click', () => {
                container.scrollBy({ left: -container.offsetWidth, behavior: 'smooth' });
            });
            nextButton.addEventListener('click', () => {
                container.scrollBy({ left: container.offsetWidth, behavior: 'smooth' });
            });

            navContainer.appendChild(prevButton);
            navContainer.appendChild(nextButton);
            container.parentNode.insertBefore(navContainer, container.nextSibling);
        }
    }
});
